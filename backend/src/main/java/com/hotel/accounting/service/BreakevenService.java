package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.BreakevenCashflowMapper;
import com.hotel.accounting.mapper.BreakevenScenarioMapper;
import com.hotel.accounting.model.BreakevenCashflow;
import com.hotel.accounting.model.BreakevenScenario;
import com.hotel.accounting.util.AuditLogger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回本测算（BE-10，03 §11）。
 *
 * <p>等额本息月供 {@code payment = LOAN×(RATE/12) / (1 − (1+RATE/12)^(−YEARS×12))}；
 * 现金流逐月 {@code running = start(−loanAmount) + Σ(netInflow − payment)}，
 * 累计首次 ≥0 即回本（上限 360 月 → 未回本）；cashflow 落 {@code breakeven_cashflow}（scenario_id+month_seq 唯一）。
 * 敏感度：月净流入 / 月供 / 投资额 三维 × {0.8,0.9,1.0,1.1,1.2} = 15 行 + base。</p>
 */
@Service
public class BreakevenService {

    /** 回本起始月（验收锚点：151 → 2039-03，58 → 2031-06）。 */
    private static final YearMonth START_MONTH = YearMonth.of(2026, 8);
    private static final int MONTH_CAP = 360;

    private final BreakevenScenarioMapper scenarioMapper;
    private final BreakevenCashflowMapper cashflowMapper;
    private final AuditLogger audit;

    public BreakevenService(BreakevenScenarioMapper scenarioMapper,
                            BreakevenCashflowMapper cashflowMapper,
                            AuditLogger audit) {
        this.scenarioMapper = scenarioMapper;
        this.cashflowMapper = cashflowMapper;
        this.audit = audit;
    }

    /** 11.2 POST /api/breakeven/scenarios：校验 → 算月供 → 生成现金流 → 同事务写两表。 */
    @Transactional
    public Map<String, Object> create(BreakevenReq req) {
        if (req == null) {
            throw BizException.badRequest("请求体不能为空");
        }
        validate(req.getInvestment(), req.getOwnCapital(), req.getLoanAmount(),
                req.getLoanRate(), req.getLoanYears(), req.getMonthlyNetInflow());
        BigDecimal loan = loanAmount(req.getInvestment(), req.getOwnCapital(), req.getLoanAmount());
        BigDecimal payment = monthlyPayment(loan, req.getLoanRate(), req.getLoanYears());

        BreakevenScenario s = new BreakevenScenario();
        s.setName(req.getName() == null || req.getName().isBlank() ? "未命名方案" : req.getName().trim());
        s.setInvestment(req.getInvestment());
        s.setOwnCapital(req.getOwnCapital() == null ? BigDecimal.ZERO : req.getOwnCapital());
        s.setLoanAmount(loan);
        s.setLoanRate(req.getLoanRate());
        s.setLoanYears(req.getLoanYears());
        s.setMonthlyPayment(payment);
        s.setMonthlyNetInflow(req.getMonthlyNetInflow());
        scenarioMapper.insert(s);

        regenerateCashflow(s.getId(), loan, payment, req.getMonthlyNetInflow());
        Integer bem = breakEvenMonth(loan, payment, req.getMonthlyNetInflow());
        audit.logAmount("BREAKEVEN_CREATE", "id=" + s.getId() + " name=" + s.getName(),
                "payment=" + payment);
        return summarize(s, bem);
    }

    /** 11.3 PUT /api/breakeven/scenarios/{id}：改任意参 → 删旧现金流重建 → 重算月供/回本。 */
    @Transactional
    public Map<String, Object> update(Long id, BreakevenReq req) {
        BreakevenScenario s = require(id);
        BigDecimal investment = req.getInvestment() == null ? s.getInvestment() : req.getInvestment();
        BigDecimal ownCapital = req.getOwnCapital() == null ? s.getOwnCapital() : req.getOwnCapital();
        BigDecimal loanRate = req.getLoanRate() == null ? s.getLoanRate() : req.getLoanRate();
        Integer loanYears = req.getLoanYears() == null ? s.getLoanYears() : req.getLoanYears();
        BigDecimal netInflow = req.getMonthlyNetInflow() == null ? s.getMonthlyNetInflow() : req.getMonthlyNetInflow();
        validate(investment, ownCapital, req.getLoanAmount(), loanRate, loanYears, netInflow);
        BigDecimal loan = loanAmount(investment, ownCapital, req.getLoanAmount());
        BigDecimal payment = monthlyPayment(loan, loanRate, loanYears);

        s.setName(req.getName() == null || req.getName().isBlank()
                ? s.getName() : req.getName().trim());
        s.setInvestment(investment);
        s.setOwnCapital(ownCapital);
        s.setLoanAmount(loan);
        s.setLoanRate(loanRate);
        s.setLoanYears(loanYears);
        s.setMonthlyPayment(payment);
        s.setMonthlyNetInflow(netInflow);
        scenarioMapper.updateById(s);

        cashflowMapper.delete(new LambdaQueryWrapper<BreakevenCashflow>()
                .eq(BreakevenCashflow::getScenarioId, id));
        regenerateCashflow(id, loan, payment, netInflow);
        Integer bem = breakEvenMonth(loan, payment, netInflow);
        audit.logAmount("BREAKEVEN_UPDATE", "id=" + id, "payment=" + payment
                + " bem=" + bem);
        return summarize(s, bem);
    }

    /** 11.4 DELETE /api/breakeven/scenarios/{id}：级联删现金流（FK CASCADE，此处显式删保险）。 */
    @Transactional
    public void delete(Long id) {
        require(id);
        cashflowMapper.delete(new LambdaQueryWrapper<BreakevenCashflow>()
                .eq(BreakevenCashflow::getScenarioId, id));
        scenarioMapper.deleteById(id);
        audit.log("BREAKEVEN_DELETE", "id=" + id);
    }

    /** 11.1 GET /api/breakeven/scenarios：列表（含 monthlyPayment/breakEvenMonth）。 */
    public Map<String, Object> list() {
        List<BreakevenScenario> rows = scenarioMapper.selectList(
                new LambdaQueryWrapper<BreakevenScenario>()
                        .orderByDesc(BreakevenScenario::getId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (BreakevenScenario s : rows) {
            Integer bem = breakEvenMonth(s.getLoanAmount(), s.getMonthlyPayment(), s.getMonthlyNetInflow());
            items.add(summarize(s, bem));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    /** 11.5 GET /api/breakeven/scenarios/{id}/cashflow：逐月现金流 + 回本月份。 */
    public Map<String, Object> cashflow(Long id) {
        BreakevenScenario s = require(id);
        Integer bem = breakEvenMonth(s.getLoanAmount(), s.getMonthlyPayment(), s.getMonthlyNetInflow());
        List<BreakevenCashflow> rows = cashflowMapper.selectList(
                new LambdaQueryWrapper<BreakevenCashflow>()
                        .eq(BreakevenCashflow::getScenarioId, id)
                        .orderByAsc(BreakevenCashflow::getMonthSeq));
        List<Map<String, Object>> rowItems = new ArrayList<>();
        for (BreakevenCashflow r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("monthSeq", r.getMonthSeq());
            m.put("inflow", r.getInflow());
            m.put("outflow", r.getOutflow());
            m.put("net", r.getNet());
            m.put("runningBalance", r.getRunningBalance());
            m.put("remark", r.getRemark());
            rowItems.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", summarize(s, bem));
        out.put("rows", rowItems);
        return out;
    }

    /** 11.6 GET /api/breakeven/scenarios/{id}/sensitivity：3 轴 × 5 点 = 15 行 + base。 */
    public Map<String, Object> sensitivity(Long id) {
        BreakevenScenario s = require(id);
        BigDecimal payment = s.getMonthlyPayment();
        BigDecimal inflow = s.getMonthlyNetInflow();
        BigDecimal loan = s.getLoanAmount();
        BigDecimal[] factors = {new BigDecimal("0.8"), new BigDecimal("0.9"),
                new BigDecimal("1.0"), new BigDecimal("1.1"), new BigDecimal("1.2")};

        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BigDecimal f : factors) {
            // 月净流入轴：net = inflow×factor − payment
            rows.add(axisRow("月净流入", f,
                    breakEvenMonth(loan, payment, inflow.multiply(f))));
            // 月供轴：payment' = payment×factor，net = inflow − payment'
            rows.add(axisRow("月供", f,
                    breakEvenMonth(loan, payment.multiply(f), inflow)));
            // 投资额轴：loan' = investment×factor − ownCapital（下限 0），payment 不变
            BigDecimal loan2 = s.getInvestment().multiply(f).subtract(s.getOwnCapital());
            if (loan2.compareTo(BigDecimal.ZERO) < 0) {
                loan2 = BigDecimal.ZERO;
            }
            rows.add(axisRow("投资额", f, breakEvenMonth(loan2, payment, inflow)));
        }
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("axis", "月净流入");
        base.put("factor", BigDecimal.ONE);
        base.put("breakEvenMonth", breakEvenMonth(loan, payment, inflow));
        out.put("base", base);
        out.put("rows", rows);
        return out;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Map<String, Object> axisRow(String axis, BigDecimal factor, Integer bem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("axis", axis);
        m.put("factor", factor);
        m.put("breakEvenMonth", bem);
        return m;
    }

    /** 等额本息月供：LOAN×(RATE/12) / (1 − (1+RATE/12)^(−n))，round(2)。 */
    private BigDecimal monthlyPayment(BigDecimal loan, BigDecimal loanRate, Integer loanYears) {
        BigDecimal m = loanRate.divide(BigDecimal.valueOf(12), 20, RoundingMode.HALF_UP);
        int n = loanYears * 12;
        BigDecimal base = BigDecimal.ONE.add(m);
        // BigDecimal.pow 只接受正整数指数：先算 (1+m)^n 再取倒数
        BigDecimal power = base.pow(n, MathContext.DECIMAL128);
        BigDecimal reciprocal = BigDecimal.ONE.divide(power, 20, RoundingMode.HALF_UP);
        BigDecimal denom = BigDecimal.ONE.subtract(reciprocal);
        return loan.multiply(m).divide(denom, 2, RoundingMode.HALF_UP);
    }

    /** 逐月现金流（落库）：start=−loan，running += (inflow−payment)，首次 ≥0 填「回本」，上限 360。 */
    private void regenerateCashflow(Long scenarioId, BigDecimal loan,
                                    BigDecimal payment, BigDecimal inflow) {
        BigDecimal running = loan.negate();
        for (int seq = 1; seq <= MONTH_CAP; seq++) {
            BigDecimal net = inflow.subtract(payment).setScale(2, RoundingMode.HALF_UP);
            running = running.add(net);
            BreakevenCashflow cf = new BreakevenCashflow();
            cf.setScenarioId(scenarioId);
            cf.setMonthSeq(seq);
            cf.setInflow(inflow);
            cf.setOutflow(payment);
            cf.setNet(net);
            cf.setRunningBalance(running.setScale(2, RoundingMode.HALF_UP));
            if (running.compareTo(BigDecimal.ZERO) >= 0) {
                cf.setRemark("回本");
                cashflowMapper.insert(cf);
                break;
            }
            cashflowMapper.insert(cf);
        }
    }

    /** 回本月份：累计起始 −loan → 首次 ≥0 的月序号；上限 360 未达 → null。 */
    private Integer breakEvenMonth(BigDecimal loan, BigDecimal payment, BigDecimal inflow) {
        BigDecimal running = loan.negate();
        for (int seq = 1; seq <= MONTH_CAP; seq++) {
            running = running.add(inflow.subtract(payment));
            if (running.compareTo(BigDecimal.ZERO) >= 0) {
                return seq;
            }
        }
        return null;
    }

    /** 贷款额：不传 = investment − ownCapital；传了必须一致，否则 40000。 */
    private BigDecimal loanAmount(BigDecimal investment, BigDecimal ownCapital, BigDecimal loanAmount) {
        BigDecimal diff = investment.subtract(ownCapital == null ? BigDecimal.ZERO : ownCapital)
                .setScale(2, RoundingMode.HALF_UP);
        if (loanAmount == null) {
            return diff;
        }
        if (loanAmount.setScale(2, RoundingMode.HALF_UP).compareTo(diff) != 0) {
            throw BizException.badRequest("loanAmount 与 investment − ownCapital 不一致");
        }
        return diff;
    }

    private void validate(BigDecimal investment, BigDecimal ownCapital, BigDecimal loanAmount,
                          BigDecimal loanRate, Integer loanYears, BigDecimal netInflow) {
        if (investment == null || investment.compareTo(BigDecimal.ZERO) <= 0) {
            throw BizException.badRequest("investment 必须 > 0");
        }
        BigDecimal oc = ownCapital == null ? BigDecimal.ZERO : ownCapital;
        if (oc.compareTo(BigDecimal.ZERO) < 0 || oc.compareTo(investment) > 0) {
            throw BizException.badRequest("ownCapital 必须在 [0, investment] 之间");
        }
        if (loanRate == null || loanRate.compareTo(BigDecimal.ZERO) <= 0
                || loanRate.compareTo(new BigDecimal("0.40")) > 0) {
            throw BizException.badRequest("loanRate 必须在 (0, 0.40] 之间（年利率，如 0.038）");
        }
        if (loanYears == null || loanYears < 1 || loanYears > 40) {
            throw BizException.badRequest("loanYears 必须在 [1, 40] 之间");
        }
        if (netInflow == null || netInflow.compareTo(BigDecimal.ZERO) < 0) {
            throw BizException.badRequest("monthlyNetInflow 必须 >= 0");
        }
    }

    private Map<String, Object> summarize(BreakevenScenario s, Integer bem) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("investment", s.getInvestment());
        m.put("ownCapital", s.getOwnCapital());
        m.put("loanAmount", s.getLoanAmount());
        m.put("loanRate", BreakevenScenario.loanRateText(s.getLoanRate()));
        m.put("loanYears", s.getLoanYears());
        m.put("monthlyPayment", s.getMonthlyPayment());
        m.put("monthlyNetInflow", s.getMonthlyNetInflow());
        m.put("breakEvenMonth", bem);
        m.put("breakEvenDate", bem == null ? null
                : START_MONTH.plusMonths(bem).format(DateTimeFormatter.ofPattern("uuuu-MM")));
        m.put("createdAt", s.getCreatedAt() == null ? null
                : s.getCreatedAt().toString().replace('T', ' '));
        return m;
    }

    private BreakevenScenario require(Long id) {
        if (id == null) {
            throw BizException.badRequest("id 必填");
        }
        BreakevenScenario s = scenarioMapper.selectById(id);
        if (s == null) {
            throw BizException.notFound("回本方案不存在: " + id);
        }
        return s;
    }

    public static class BreakevenReq {
        private String name;
        private BigDecimal investment;
        private BigDecimal ownCapital;
        private BigDecimal loanAmount;
        private BigDecimal loanRate;
        private Integer loanYears;
        private BigDecimal monthlyNetInflow;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public BigDecimal getInvestment() {
            return investment;
        }

        public void setInvestment(BigDecimal investment) {
            this.investment = investment;
        }

        public BigDecimal getOwnCapital() {
            return ownCapital;
        }

        public void setOwnCapital(BigDecimal ownCapital) {
            this.ownCapital = ownCapital;
        }

        public BigDecimal getLoanAmount() {
            return loanAmount;
        }

        public void setLoanAmount(BigDecimal loanAmount) {
            this.loanAmount = loanAmount;
        }

        public BigDecimal getLoanRate() {
            return loanRate;
        }

        public void setLoanRate(BigDecimal loanRate) {
            this.loanRate = loanRate;
        }

        public Integer getLoanYears() {
            return loanYears;
        }

        public void setLoanYears(Integer loanYears) {
            this.loanYears = loanYears;
        }

        public BigDecimal getMonthlyNetInflow() {
            return monthlyNetInflow;
        }

        public void setMonthlyNetInflow(BigDecimal monthlyNetInflow) {
            this.monthlyNetInflow = monthlyNetInflow;
        }
    }
}
