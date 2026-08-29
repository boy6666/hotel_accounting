package com.hotel.accounting.client;

import com.hotel.accounting.common.BizException;
import com.hotel.accounting.config.SidecarProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 旁车（FastAPI §14，仅 127.0.0.1）调用客户端。旁车不可用/超时 → 统一抛 50100，
 * 由上层降级提示"智能服务暂不可用，仍可手动归类/纯日历预测"。
 */
@Component
public class SidecarClient {

    private static final Logger log = LoggerFactory.getLogger(SidecarClient.class);

    private final SidecarProperties props;
    private final RestClient restClient;

    public SidecarClient(SidecarProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) props.getTimeoutMs());
        f.setReadTimeout((int) props.getTimeoutMs());
        this.restClient = RestClient.builder()
                .requestFactory(f)
                .build();
    }

    /**
     * 14.1 解析 Excel：主后端先把 Excel 落盘，再把绝对路径以 JSON {file_path, month} 交给旁车（旁车与主后端同机，
     * 直接读盘）。返回结构化三表 {@code data}（已剥信封）。旁车不可用抛 50100。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseExcel(String filePath, String month) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("file_path", filePath);
        body.put("month", month);
        try {
            Map<String, Object> resp = restClient.post()
                    .uri(props.getBaseUrl() + "/api/parse")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                throw BizException.sidecarUnavailable("旁车解析无响应");
            }
            Object data = resp.get("data");
            if (!(data instanceof Map)) {
                throw BizException.sidecarUnavailable("旁车解析结果格式异常");
            }
            return (Map<String, Object>) data;
        } catch (ResourceAccessException e) {
            log.warn("旁车不可用（{} 超时/连接失败），抛 50100 降级", props.getBaseUrl(), e);
            throw BizException.sidecarUnavailable("智能服务暂不可用，仍可手动归类/纯日历预测");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("旁车 /api/parse 调用异常", e);
            throw BizException.sidecarUnavailable("智能服务暂不可用，仍可手动归类/纯日历预测");
        }
    }

    /** 14.5 健康检查（旁车故障返回 false，不抛异常）。 */
    public boolean health() {
        try {
            Map<String, Object> resp = restClient.get()
                    .uri(props.getBaseUrl() + "/api/health")
                    .retrieve()
                    .body(Map.class);
            return resp != null && Boolean.TRUE.equals(resp.get("ok"));
        } catch (Exception e) {
            log.debug("旁车健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 14.3 时序预测：body 由主后端汇总历史（不含明细行）后提交。返回旁车 {@code data}（已剥信封）。
     * 旁车不可用/超时/信封非 0 → 抛 50100，由上层降级为纯统计兜底（仍 200 + degraded=true）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> predict(Map<String, Object> body) {
        return postMap("/api/predict", body, "预测");
    }

    /**
     * 14.4 LLM 解读：只送聚合摘要（不含身份/明细）。返回解读文本；旁车不可用抛 50100
     * （上层非阻塞置 null）。data 既可能是字符串，也可能是 {@code {interpretation: ...}} 结构，
     * 两种情况都兼容。
     */
    public String llmInterpret(Map<String, Object> body) {
        try {
            Map<String, Object> resp = restClient.post()
                    .uri(props.getBaseUrl() + "/api/llm/interpret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                throw BizException.sidecarUnavailable("旁车解读无响应");
            }
            Object code = resp.get("code");
            if (code != null && !(code instanceof Number num && num.intValue() == 0)) {
                throw BizException.sidecarUnavailable("旁车解读返回错误：" + resp.get("message"));
            }
            Object data = resp.get("data");
            if (data == null) {
                return null;
            }
            if (data instanceof String s) {
                return s;
            }
            if (data instanceof Map<?, ?> m) {
                Object v = m.get("interpretation");
                if (v == null) {
                    v = m.get("text");
                }
                return v == null ? null : String.valueOf(v);
            }
            return String.valueOf(data);
        } catch (ResourceAccessException e) {
            log.warn("旁车不可用 llmInterpret（{} 超时/连接失败）", props.getBaseUrl(), e);
            throw BizException.sidecarUnavailable("智能服务暂不可用，仍可纯统计预测");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("旁车 /api/llm/interpret 调用异常", e);
            throw BizException.sidecarUnavailable("智能服务暂不可用，仍可纯统计预测");
        }
    }

    /** 14.3/14.1 风格 POST：Json body → 剥信封返回 Map data；失败统一映射 50100。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postMap(String path, Map<String, Object> body, String tag) {
        try {
            Map<String, Object> resp = restClient.post()
                    .uri(props.getBaseUrl() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null) {
                throw BizException.sidecarUnavailable("旁车" + tag + "无响应");
            }
            Object code = resp.get("code");
            if (code != null && !(code instanceof Number num && num.intValue() == 0)) {
                throw BizException.sidecarUnavailable("旁车" + tag + "返回错误：" + resp.get("message"));
            }
            Object data = resp.get("data");
            if (!(data instanceof Map)) {
                throw BizException.sidecarUnavailable("旁车" + tag + "结果格式异常");
            }
            return (Map<String, Object>) data;
        } catch (ResourceAccessException e) {
            log.warn("旁车不可用 {}（{} 超时/连接失败），抛 50100 降级", path, props.getBaseUrl(), e);
            throw BizException.sidecarUnavailable("智能服务暂不可用，仍可纯统计预测");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("旁车 {} 调用异常", path, e);
            throw BizException.sidecarUnavailable("智能服务暂不可用，仍可纯统计预测");
        }
    }
}
