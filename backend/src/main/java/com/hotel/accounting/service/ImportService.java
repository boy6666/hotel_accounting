package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.accounting.client.SidecarClient;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.common.PageResult;
import com.hotel.accounting.config.StorageProperties;
import com.hotel.accounting.mapper.ChannelMonthlyMapper;
import com.hotel.accounting.mapper.DailyOccupancyMapper;
import com.hotel.accounting.mapper.DailyOccupiedRoomMapper;
import com.hotel.accounting.mapper.ImportBatchMapper;
import com.hotel.accounting.mapper.MonthlyCostMapper;
import com.hotel.accounting.mapper.RoomMapper;
import com.hotel.accounting.model.Channel;
import com.hotel.accounting.model.ChannelMonthly;
import com.hotel.accounting.model.DailyOccupancy;
import com.hotel.accounting.model.ImportBatch;
import com.hotel.accounting.model.ImportMappingRule;
import com.hotel.accounting.model.MonthlyCost;
import com.hotel.accounting.model.Room;
import com.hotel.accounting.util.AuditLogger;
import com.hotel.accounting.util.Months;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.regex.Pattern;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Excel 月度记账导入（BE-08，03 §12）—— 导入闭环核心。
 *
 * <p>状态机：{@code uploaded → parsed/mapped → confirmed / failed}。上传 → 调旁车 /api/parse
 * （不可用抛 50100 降级，用户仍可走手动录入各接口）→ 存解析 JSON → preview/mapping 供前端确认 →
 * confirm 事务内替换所选月份 {@code monthly_cost} + {@code channel_monthly}、覆盖出现在导入中的房态日期，
 * 自动建档 cost_items/channels/rooms 并记忆 {@code import_mapping_rule}（is_manual=1）→ 重算 + 对账。</p>
 *
 * <p>同月重复导入（存在非 failed 批次）返回 40900；删旧批次后可重导。</p>
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportBatchMapper importBatchMapper;
    private final SidecarClient sidecarClient;
    private final StorageProperties storage;
    private final ObjectMapper objectMapper;
    private final CostItemService costItemService;
    private final MappingRuleService mappingRuleService;
    private final ChannelService channelService;
    private final RoomService roomService;
    private final RoomMapper roomMapper;
    private final MonthlyCostMapper monthlyCostMapper;
    private final ChannelMonthlyMapper channelMonthlyMapper;
    private final DailyOccupancyMapper dailyOccupancyMapper;
    private final DailyOccupiedRoomMapper dailyOccupiedRoomMapper;
    private final RecalcService recalcService;
    private final SettingsService settingsService;
    private final AuditLogger audit;

    public ImportService(ImportBatchMapper importBatchMapper,
                         SidecarClient sidecarClient,
                         StorageProperties storage,
                         ObjectMapper objectMapper,
                         CostItemService costItemService,
                         MappingRuleService mappingRuleService,
                         ChannelService channelService,
                         RoomService roomService,
                         RoomMapper roomMapper,
                         MonthlyCostMapper monthlyCostMapper,
                         ChannelMonthlyMapper channelMonthlyMapper,
                         DailyOccupancyMapper dailyOccupancyMapper,
                         DailyOccupiedRoomMapper dailyOccupiedRoomMapper,
                         RecalcService recalcService,
                         SettingsService settingsService,
                         AuditLogger audit) {
        this.importBatchMapper = importBatchMapper;
        this.sidecarClient = sidecarClient;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.costItemService = costItemService;
        this.mappingRuleService = mappingRuleService;
        this.channelService = channelService;
        this.roomService = roomService;
        this.roomMapper = roomMapper;
        this.monthlyCostMapper = monthlyCostMapper;
        this.channelMonthlyMapper = channelMonthlyMapper;
        this.dailyOccupancyMapper = dailyOccupancyMapper;
        this.dailyOccupiedRoomMapper = dailyOccupiedRoomMapper;
        this.recalcService = recalcService;
        this.settingsService = settingsService;
        this.audit = audit;
    }

    /** 12.2 上传 Excel，进入 parsed/mapped。 */
    @Transactional
    public Map<String, Object> upload(MultipartFile file, String month) {
        Months.require(month);
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("file 不能为空");
        }
        boolean dup = importBatchMapper.selectList(
                        new LambdaQueryWrapper<ImportBatch>().eq(ImportBatch::getMonth, month)).stream()
                .anyMatch(b -> !"failed".equals(b.getStatus()));
        if (dup) {
            throw BizException.conflict("该月份已有导入批次，请先删除旧批次再导入（" + month + "）");
        }

        String fileName = file.getOriginalFilename() == null ? "month.xlsx" : file.getOriginalFilename();
        Path saved = saveUpload(month, fileName, file);

        ImportBatch batch = new ImportBatch();
        batch.setTemplateType("sales"); // 合簿批次：三表一体；ENUM 无法表示全部，取 sales 作全量标记
        batch.setMonth(month);
        batch.setFileName(fileName);
        batch.setFilePath(saved.toString());
        batch.setStatus("uploaded");
        batch.setTotalRows(0);
        batch.setFailedRows(0);
        importBatchMapper.insert(batch);

        Map<String, Object> parsed;
        try {
            // 旁车与主后端同机：把已落盘 xlsx 的绝对路径交给旁车读取（14.1 契约 JSON）
            parsed = sidecarClient.parseExcel(saved.toString(), month);
        } catch (BizException e) {
            batch.setStatus("failed");
            batch.setErrorMessage(e.getMessage());
            importBatchMapper.updateById(batch);
            throw e;
        }
        writeParsed(batch.getId(), parsed);

        int totalRows = size(parsed.get("costs")) + size(parsed.get("occupancy")) + size(parsed.get("channels"));
        // 归类建议由后端字典/学习规则即时生成（/mapping），解析成功即有 → 置 mapped
        batch.setStatus("mapped");
        batch.setTotalRows(totalRows);
        batch.setFailedRows(0);
        batch.setRawName(rawNameSummary(parsed));
        importBatchMapper.updateById(batch);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batch.getId());
        out.put("status", batch.getStatus());
        out.put("month", month);
        out.put("fileName", fileName);
        out.put("totalRows", totalRows);
        out.put("failedRows", 0);
        out.put("rawNameSummary", batch.getRawName());
        return out;
    }

    /** 12.3 批次详情。 */
    public Map<String, Object> detail(Long id) {
        ImportBatch b = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", b.getId());
        out.put("templateType", b.getTemplateType());
        out.put("month", b.getMonth());
        out.put("fileName", b.getFileName());
        out.put("status", b.getStatus());
        out.put("totalRows", b.getTotalRows());
        out.put("failedRows", b.getFailedRows());
        out.put("rawName", b.getRawName());
        out.put("errorMessage", b.getErrorMessage());
        out.put("createdAt", b.getCreatedAt() == null ? null : b.getCreatedAt().toString().replace('T', ' '));
        return out;
    }

    /**
     * 12.4 解析后三表预览（对齐前端 mock 契约，交前端零改动联调）。
     *
     * <p>返回 {@code sheets.{costs,occupancy,sales}}，其中房态展开为<strong>扁平行级明细</strong>
     * （每间 1 行：{@code bizDate/date + roomNo}），并按字典是否已建档标注 {@code known}
     * （{@code known=false} 即「确认时自动建档」项，前端用于提示与 roomRows 组装）。</p>
     */
    public Map<String, Object> preview(Long id) {
        ImportBatch b = require(id);
        Map<String, Object> parsed = readParsed(id);

        List<Map<String, Object>> costs = new ArrayList<>();
        for (Map<String, Object> c : rows(parsed.get("costs"))) {
            String rawName = str(c.get("rawName"));
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            Map<String, Object> sug = suggestCostItem(rawName);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("rowNo", intOf(c.get("rowNo")));
            e.put("rawName", rawName);
            e.put("itemName", rawName);
            e.put("amount", dec(c.get("amount")));
            e.put("type", str(c.get("type")));
            e.put("suggestType", str(c.get("suggestType")));
            e.put("confidence", sug.get("confidence"));
            e.put("matched", sug.get("matched"));
            e.put("known", sug.get("matched")); // 已建档 = 确认时不自动建档
            e.put("note", str(c.get("note")));
            costs.add(e);
        }

        List<Map<String, Object>> occupancy = new ArrayList<>();
        Map<String, String> roomTypeByNo = new LinkedHashMap<>();
        for (Map<String, Object> rm : rows(parsed.get("rooms"))) {
            String no = str(rm.get("roomNo"));
            if (no != null && !no.isBlank()) {
                roomTypeByNo.put(no.trim(), str(rm.get("roomType")));
            }
        }
        for (Map<String, Object> r : rows(parsed.get("occupancy"))) {
            LocalDate d = parseDate(str(r.get("bizDate")));
            Object nos = r.get("roomNos");
            if (d == null || !(nos instanceof List<?> list)) {
                continue; // 旧房型计数布局无具体房号 → 无法展开行级房号，预览略过
            }
            for (Object no : list) {
                String roomNo = no == null ? null : String.valueOf(no).trim();
                if (roomNo == null || roomNo.isBlank()) {
                    continue;
                }
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("bizDate", d.toString());
                e.put("date", d.toString());
                e.put("roomNo", roomNo);
                e.put("roomType", roomTypeByNo.getOrDefault(roomNo, ""));
                e.put("known", roomMapper.selectByRoomNo(roomNo) != null);
                occupancy.add(e);
            }
        }

        List<Map<String, Object>> sales = new ArrayList<>();
        for (Map<String, Object> ch : rows(parsed.get("channels"))) {
            String rawName = str(ch.get("rawName"));
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("rowNo", intOf(ch.get("rowNo")));
            e.put("rawName", rawName);
            e.put("nights", intOf(ch.get("nights")));
            e.put("revenue", dec(ch.get("revenue")));
            e.put("known", isKnownChannel(rawName)); // 与 confirm 归并口径一致：精确/归一化命中即不再建档
            e.put("note", str(ch.get("note")));
            sales.add(e);
        }

        Map<String, Object> sheets = new LinkedHashMap<>();
        sheets.put("costs", costs);
        sheets.put("occupancy", occupancy);
        sheets.put("sales", sales);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", id);
        out.put("month", b.getMonth());
        out.put("fileName", b.getFileName());
        out.put("status", b.getStatus());
        out.put("sheets", sheets);
        return out;
    }

    /** 12.5 智能归类建议（字典/学习规则即时生成，12.5 响应）。 */
    public Map<String, Object> mapping(Long id) {
        require(id);
        Map<String, Object> parsed = readParsed(id);
        List<Map<String, Object>> items = new ArrayList<>();
        int confirmed = 0;
        int needReview = 0;
        for (Map<String, Object> c : rows(parsed.get("costs"))) {
            String rawName = str(c.get("rawName"));
            Map<String, Object> suggest = suggestCostItem(rawName);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("rowNo", intOf(c.get("rowNo")));
            e.put("rawName", rawName);
            e.put("suggestCostItemId", suggest.get("costItemId"));
            e.put("suggestType", suggest.get("type"));
            e.put("confidence", suggest.get("confidence"));
            boolean matched = Boolean.TRUE.equals(suggest.get("matched"));
            e.put("matched", matched);
            if (matched) {
                confirmed++;
            } else {
                needReview++;
            }
            items.add(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("confirmed", confirmed);
        out.put("needReview", needReview);
        out.put("items", items);
        return out;
    }

    /** 12.6 确认落库（事务）：替换成本/渠道，覆盖房态日期，自动建档，重算对账。 */
    @Transactional
    public Map<String, Object> confirm(Long id, ConfirmReq req) {
        ImportBatch batch = require(id);
        if ("confirmed".equals(batch.getStatus())) {
            throw BizException.conflict("批次已确认，无法重复确认: id=" + id);
        }
        String month = batch.getMonth();
        Map<String, Object> parsed = readParsed(id);

        Set<String> createdCostItems = new LinkedHashSet<>();
        Set<String> createdChannels = new LinkedHashSet<>();
        Set<String> createdRooms = new LinkedHashSet<>();
        int importedNights = 0;

        // ---- 1) monthly_cost：替换该月 ----
        monthlyCostMapper.delete(new LambdaQueryWrapper<MonthlyCost>().eq(MonthlyCost::getMonth, month));
        for (Map<String, Object> c : rows(parsed.get("costs"))) {
            String rawName = str(c.get("rawName"));
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            MappingItem map = findMapping(req, rawName);
            String type = pick(str(c.get("type")),
                    map == null || map.getType() == null ? null : map.getType());
            type = type == null ? "variable" : type;
            if (!Set.of("fixed", "variable", "one_time").contains(type)) {
                type = "variable";
            }
            Long costItemId = map == null ? null : map.getCostItemId();
            com.hotel.accounting.model.CostItem costItem;
            if (costItemId != null) {
                costItem = costItemService.require(costItemId);
            } else {
                boolean existed = costItemService.findByExactName(rawName) != null;
                costItem = costItemService.createIfMissing(rawName, type, batch.getId());
                if (!existed) {
                    createdCostItems.add(costItem.getName());
                }
            }
            mappingRuleService.record(rawName, costItem.getId(), costItem.getDefaultType(),
                    BigDecimal.ONE, true);

            MonthlyCost mc = new MonthlyCost();
            mc.setMonth(month);
            mc.setCostItemId(costItem.getId());
            mc.setItemName(rawName);
            mc.setAmount(dec(c.get("amount")));
            mc.setType(costItem.getDefaultType());
            mc.setNote(str(c.get("note")));
            mc.setSource("import");
            mc.setImportBatchId(batch.getId());
            monthlyCostMapper.insert(mc);
        }

        // ---- 2) channel_monthly：替换该月 ----
        channelMonthlyMapper.delete(new LambdaQueryWrapper<ChannelMonthly>()
                .eq(ChannelMonthly::getMonth, month));
        for (Map<String, Object> r : rows(parsed.get("channels"))) {
            String rawName = str(r.get("rawName"));
            if (rawName == null || rawName.isBlank()) {
                continue;
            }
            ChannelMapping map = findChannelMapping(req, rawName);
            Channel ch = resolveChannel(rawName, map, createdChannels);
            int nights = intOf(r.get("nights"));
            importedNights += nights;
            ChannelMonthly cm = new ChannelMonthly();
            cm.setMonth(month);
            cm.setChannelId(ch.getId());
            cm.setNights(nights);
            cm.setRevenue(dec(r.get("revenue")));
            cm.setNote(str(r.get("note")));
            channelMonthlyMapper.insert(cm);
        }

        // ---- 3) 房态：覆盖导入中出现的日期 ----
        Map<String, String> roomTypeByNo = new LinkedHashMap<>();
        if (req != null && req.getRoomRows() != null) {
            for (RoomMapping rm : req.getRoomRows()) {
                if (rm.getRoomNo() != null && !rm.getRoomNo().isBlank()) {
                    roomTypeByNo.put(rm.getRoomNo().trim(), rm.getRoomType());
                }
            }
        }
        Map<LocalDate, List<String>> occByDate = new LinkedHashMap<>();
        for (Map<String, Object> r : rows(parsed.get("occupancy"))) {
            LocalDate d = parseDate(str(r.get("bizDate")));
            if (d == null) {
                continue;
            }
            List<String> roomNos = roomNosOf(r);
            occByDate.put(d, roomNos);
        }
        for (Map.Entry<LocalDate, List<String>> e : occByDate.entrySet()) {
            LocalDate d = e.getKey();
            dailyOccupiedRoomMapper.deleteByBizDate(d);
            for (String no : e.getValue()) {
                boolean isNew = roomMapper.selectByRoomNo(no) == null;
                Room room = roomService.findOrCreateRoom(no, roomTypeByNo.getOrDefault(no, null), null);
                if (isNew) {
                    createdRooms.add(room.getRoomNo());
                }
                String ty = roomTypeByNo.get(no);
                if (ty != null && !ty.isBlank()
                        && (room.getRoomType() == null || room.getRoomType().isBlank())) {
                    room.setRoomType(ty); // 已建档但房型为空 → 本次导入补齐（不覆盖手填值）
                    roomMapper.updateById(room);
                }
                dailyOccupiedRoomMapper.insertRow(d, room.getId());
            }
            upsertDailyImport(d, e.getValue().size(), batch.getId());
        }

        // ---- 4) 重算 + 对账 + 置为 confirmed ----
        recalcService.recalc(month);
        ReconcileInfo ri = recalcService.reconcile(month);
        batch.setStatus("confirmed");
        batch.setErrorMessage(null);
        importBatchMapper.updateById(batch);
        audit.logAmount("IMPORT_CONFIRM", "batch=" + id + " month=" + month,
                "costs=" + rows(parsed.get("costs")).size() + " nights=" + importedNights);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batch.getId());
        out.put("status", "confirmed");
        out.put("createdCostItems", new ArrayList<>(createdCostItems));
        out.put("createdChannels", new ArrayList<>(createdChannels));
        out.put("createdRooms", new ArrayList<>(createdRooms));
        out.put("importedCosts", rows(parsed.get("costs")).size());
        out.put("importedNights", importedNights);
        out.put("reconcileStatus", ri.getStatus());
        out.put("reconcileDiff", ri.getDiff());
        return out;
    }

    /** 12.7 导入历史（分页）。 */
    public PageResult<ImportBatch> list(String month, String status, long page, long pageSize) {
        LambdaQueryWrapper<ImportBatch> qw = new LambdaQueryWrapper<ImportBatch>()
                .orderByDesc(ImportBatch::getId);
        if (month != null && !month.isBlank()) {
            Months.require(month);
            qw.eq(ImportBatch::getMonth, month);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(ImportBatch::getStatus, status);
        }
        List<ImportBatch> all = importBatchMapper.selectList(qw);
        long total = all.size();
        int fromIdx = (int) Math.min(total, (page - 1) * pageSize);
        int toIdx = (int) Math.min(total, fromIdx + pageSize);
        return PageResult.of(all.subList(fromIdx, toIdx), total, page, pageSize);
    }

    /** 删除旧批次（腾出同月重导位）。 */
    public void delete(Long id) {
        ImportBatch b = require(id);
        deleteFiles(b);
        importBatchMapper.deleteById(id);
        audit.log("DELETE_IMPORT_BATCH", "batch=" + id + " month=" + b.getMonth());
    }

    public ImportBatch require(Long id) {
        ImportBatch b = importBatchMapper.selectById(id);
        if (b == null) {
            throw BizException.notFound("导入批次不存在: id=" + id);
        }
        return b;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private Map<String, Object> suggestCostItem(String rawName) {
        Map<String, Object> out = new LinkedHashMap<>();
        com.hotel.accounting.model.CostItem item = costItemService.findByExactName(rawName);
        if (item != null && (item.getEnabled() == null || item.getEnabled() == 1)) {
            out.put("costItemId", item.getId());
            out.put("type", item.getDefaultType());
            out.put("confidence", new BigDecimal("1.0000"));
            out.put("matched", true);
            return out;
        }
        ImportMappingRule rule = mappingRuleService.findByRawName(rawName);
        if (rule != null) {
            out.put("costItemId", rule.getCostItemId());
            out.put("type", rule.getType() == null ? "variable" : rule.getType());
            out.put("confidence", rule.getConfidence() == null
                    ? new BigDecimal("0.5000") : rule.getConfidence());
            out.put("matched", rule.getCostItemId() != null);
            return out;
        }
        out.put("costItemId", null);
        out.put("type", "variable");
        out.put("confidence", new BigDecimal("0.0000"));
        out.put("matched", false);
        return out;
    }

    private MappingItem findMapping(ConfirmReq req, String rawName) {
        if (req == null || req.getMappings() == null) {
            return null;
        }
        String n = rawName == null ? "" : rawName.trim();
        return req.getMappings().stream()
                .filter(m -> m.getRawName() != null && n.equals(m.getRawName().trim()))
                .findFirst().orElse(null);
    }

    private ChannelMapping findChannelMapping(ConfirmReq req, String rawName) {
        if (req == null || req.getChannelRows() == null) {
            return null;
        }
        String n = rawName == null ? "" : rawName.trim();
        return req.getChannelRows().stream()
                .filter(m -> m.getRawName() != null && n.equals(m.getRawName().trim()))
                .findFirst().orElse(null);
    }

    /** 导入渠道归并：精确名 → 归一化名（容忍 '飞猪/去哪儿' ↔ '飞猪 / 去哪儿' 的空格/标点差）→ 自动建档。 */
    private Channel resolveChannel(String rawName, ChannelMapping map, Set<String> createdChannels) {
        Long channelId = map == null ? null : map.getChannelId();
        if (channelId != null) {
            return channelService.requireChannel(channelId);
        }
        Channel ch = findKnownChannel(rawName);
        if (ch == null) {
            ch = channelService.createChannel(autoChannelReq(rawName));
            createdChannels.add(ch.getName());
        }
        return ch;
    }

    /** 已知渠道（精确名 → 归一化名），未命中返回 null。 */
    private Channel findKnownChannel(String rawName) {
        Channel ch = channelService.findByExactName(rawName);
        if (ch != null) {
            return ch;
        }
        String norm = normalizeName(rawName);
        if (norm == null) {
            return null;
        }
        return channelService.listChannels(null, null).stream()
                .filter(c -> norm.equals(normalizeName(c.getName())))
                .findFirst().orElse(null);
    }

    private boolean isKnownChannel(String rawName) {
        return findKnownChannel(rawName) != null;
    }

    private ChannelService.ChannelReq autoChannelReq(String name) {
        // 契约（07 BE-08 / 12.6）：未匹配渠道默认线上，佣金率取 default_commission_rate；
        // 仅明显的线下渠道名降级 offline（佣金率置 0，由 RecalcService 按 type 判定）。
        String t = isOfflineChannelName(name) ? "offline" : "online";
        Object rate = settingsService.getHotel().get("defaultCommissionRate");
        ChannelService.ChannelReq req = new ChannelService.ChannelReq();
        req.setName(name.trim());
        req.setType(t);
        req.setCommissionRate(t.equals("offline") ? BigDecimal.ZERO
                : (rate instanceof BigDecimal bd ? bd : BigDecimal.ZERO));
        req.setEnabled(true);
        return req;
    }

    private static final String[] OFFLINE_CHANNEL_MARKERS = {"前台", "散客", "协议", "中介", "线下", "直客", "自营", "钟点", "自来客"};

    private static boolean isOfflineChannelName(String name) {
        String n = normalizeName(name);
        if (n == null) {
            return false;
        }
        for (String m : OFFLINE_CHANNEL_MARKERS) {
            if (n.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** 渠道名/费用名归一化（对齐旁车 normalize_text：NFKC + 去空白标点），用于导入归并。 */
    private static final Pattern NAME_PUNCT = Pattern.compile(
            "[\\s·、/\\\\\\-_()（）【】\\[\\]{}「」『』:：,，.．~～!！?？*＊+＋|]+");

    private static String normalizeName(String s) {
        if (s == null) {
            return null;
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).trim();
        return n.isEmpty() ? null : NAME_PUNCT.matcher(n).replaceAll("");
    }

    private void upsertDailyImport(LocalDate d, int occupied, Long batchId) {
        DailyOccupancy row = dailyOccupancyMapper.selectOne(
                new LambdaQueryWrapper<DailyOccupancy>().eq(DailyOccupancy::getBizDate, d));
        boolean isNew = row == null;
        if (isNew) {
            row = new DailyOccupancy();
            row.setBizDate(d);
        }
        row.setOccupiedRooms(occupied);
        row.setTotalRooms(recalcService.enabledRoomCount());
        row.setSource("import");
        row.setImportBatchId(batchId);
        if (isNew) {
            dailyOccupancyMapper.insert(row);
        } else {
            dailyOccupancyMapper.updateById(row);
        }
    }

    private List<String> roomNosOf(Map<String, Object> r) {
        Object nos = r.get("roomNos");
        if (nos instanceof List<?> list) {
            return list.stream().map(Objects::toString).collect(Collectors.toList());
        }
        Object count = r.get("count");
        int n = count instanceof Number num ? num.intValue() : 0;
        if (n > 0) {
            List<String> enabled = enabledRoomNos();
            return n >= enabled.size() ? enabled : enabled.subList(0, n);
        }
        return List.of();
    }

    private List<String> enabledRoomNos() {
        return roomMapper.selectList(new LambdaQueryWrapper<Room>()
                        .eq(Room::getEnabled, 1)
                        .orderByAsc(Room::getSortOrder).orderByAsc(Room::getRoomNo))
                .stream().map(Room::getRoomNo).collect(Collectors.toList());
    }

    // ---- 文件与解析 JSON 存取 ----

    private Path saveUpload(String month, String fileName, MultipartFile file) {
        try {
            Path dir = Paths.get(storage.getUploadDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            String safe = fileName.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
            Path target = dir.resolve(month + "-" + UUID.randomUUID().toString().substring(0, 8) + "-" + safe);
            file.transferTo(target.toFile());
            return target;
        } catch (IOException e) {
            throw BizException.internal("保存上传文件失败: " + e.getMessage());
        }
    }

    private void writeParsed(Long batchId, Map<String, Object> parsed) {
        try {
            Path dir = Paths.get(storage.getParsedDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(Paths.get(storage.getParsedDir(), "parsed-" + batchId + ".json").toFile(), parsed);
        } catch (IOException e) {
            throw BizException.internal("解析结果落盘失败: " + e.getMessage());
        }
    }

    private Map<String, Object> readParsed(Long batchId) {
        try {
            Path p = Paths.get(storage.getParsedDir(), "parsed-" + batchId + ".json").toAbsolutePath().normalize();
            if (!Files.exists(p)) {
                throw BizException.internal("解析结果缺失（旁车可能未成功）: batch=" + batchId);
            }
            return objectMapper.readValue(p.toFile(), new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw BizException.internal("读取解析结果失败: " + e.getMessage());
        }
    }

    private void deleteFiles(ImportBatch b) {
        try {
            if (b.getFilePath() != null) {
                Files.deleteIfExists(Paths.get(b.getFilePath()));
            }
        } catch (IOException ignored) {
            // 文件缺失不影响删除批次
        }
        try {
            Files.deleteIfExists(Paths.get(storage.getParsedDir(), "parsed-" + b.getId() + ".json"));
        } catch (IOException ignored) {
        }
    }

    private String rawNameSummary(Map<String, Object> parsed) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> c : rows(parsed.get("costs"))) {
            if (str(c.get("rawName")) != null) {
                names.add(str(c.get("rawName")));
            }
        }
        for (Map<String, Object> c : rows(parsed.get("channels"))) {
            if (str(c.get("rawName")) != null) {
                names.add(str(c.get("rawName")));
            }
        }
        String joined = String.join("/", names);
        return joined.length() > 100 ? joined.substring(0, 100) + "…" : joined;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Object o) {
        if (o instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    private static int size(Object o) {
        return o instanceof List<?> list ? list.size() : 0;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static BigDecimal dec(Object o) {
        if (o == null) {
            return BigDecimal.ZERO;
        }
        if (o instanceof BigDecimal bd) {
            return bd;
        }
        if (o instanceof Number num) {
            return new BigDecimal(num.toString());
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            log.warn("金额解析失败，置 0: {}", o);
            return BigDecimal.ZERO;
        }
    }

    private static int intOf(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number num) {
            return num.intValue();
        }
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(o).trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String pick(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static class ConfirmReq {
        private List<MappingItem> mappings;
        private List<ChannelMapping> channelRows;
        private List<RoomMapping> roomRows;

        public List<MappingItem> getMappings() {
            return mappings;
        }

        public void setMappings(List<MappingItem> mappings) {
            this.mappings = mappings;
        }

        public List<ChannelMapping> getChannelRows() {
            return channelRows;
        }

        public void setChannelRows(List<ChannelMapping> channelRows) {
            this.channelRows = channelRows;
        }

        public List<RoomMapping> getRoomRows() {
            return roomRows;
        }

        public void setRoomRows(List<RoomMapping> roomRows) {
            this.roomRows = roomRows;
        }
    }

    public static class MappingItem {
        private String rawName;
        private Long costItemId;
        private String type;

        public String getRawName() {
            return rawName;
        }

        public void setRawName(String rawName) {
            this.rawName = rawName;
        }

        public Long getCostItemId() {
            return costItemId;
        }

        public void setCostItemId(Long costItemId) {
            this.costItemId = costItemId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    public static class ChannelMapping {
        private String rawName;
        private Long channelId;

        public String getRawName() {
            return rawName;
        }

        public void setRawName(String rawName) {
            this.rawName = rawName;
        }

        public Long getChannelId() {
            return channelId;
        }

        public void setChannelId(Long channelId) {
            this.channelId = channelId;
        }
    }

    public static class RoomMapping {
        private String roomNo;
        private String roomType;

        public String getRoomNo() {
            return roomNo;
        }

        public void setRoomNo(String roomNo) {
            this.roomNo = roomNo;
        }

        public String getRoomType() {
            return roomType;
        }

        public void setRoomType(String roomType) {
            this.roomType = roomType;
        }
    }
}
