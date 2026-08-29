package com.hotel.accounting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hotel.accounting.common.BizException;
import com.hotel.accounting.mapper.DailyOccupancyMapper;
import com.hotel.accounting.mapper.DailyOccupiedRoomMapper;
import com.hotel.accounting.mapper.RoomMapper;
import com.hotel.accounting.model.DailyOccupancy;
import com.hotel.accounting.model.DailyOccupiedRoom;
import com.hotel.accounting.model.Room;
import com.hotel.accounting.util.AuditLogger;
import com.hotel.accounting.util.HolidayUtil;
import com.hotel.accounting.util.Months;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 房态 · 入住率（BE-04，03 §9）。
 *
 * <p>按具体房间登记（哪天哪几间住了）→ 写 {@code daily_occupied_room} 明细 →
 * 刷新 {@code daily_occupancy} 聚合（occupied_rooms/total_rooms）→ 重算 {@code monthly_summary} 与对账。
 * 房号未建档（{@code room} 表查不到）时自动建档。</p>
 *
 * <p>覆盖式语义：PUT day-rooms 先删当日旧明细再写新明细；roomNos 为空 = 该日全部空房（还要落 0 行聚合，
 * 使该日在入住率分母中计为"有记录"）。</p>
 */
@Service
public class OccupancyService {

    private final DailyOccupancyMapper dailyOccupancyMapper;
    private final DailyOccupiedRoomMapper dailyOccupiedRoomMapper;
    private final RoomMapper roomMapper;
    private final RoomService roomService;
    private final RecalcService recalcService;
    private final AuditLogger audit;

    public OccupancyService(DailyOccupancyMapper dailyOccupancyMapper,
                            DailyOccupiedRoomMapper dailyOccupiedRoomMapper,
                            RoomMapper roomMapper,
                            RoomService roomService,
                            RecalcService recalcService,
                            AuditLogger audit) {
        this.dailyOccupancyMapper = dailyOccupancyMapper;
        this.dailyOccupiedRoomMapper = dailyOccupiedRoomMapper;
        this.roomMapper = roomMapper;
        this.roomService = roomService;
        this.recalcService = recalcService;
        this.audit = audit;
    }

    /** 9.1 该月每日聚合列表（含工作日标记、入住率）。 */
    public Map<String, Object> daily(String month) {
        YearMonth ym = Months.require(month);
        LocalDate from = Months.firstDay(ym);
        LocalDate to = Months.lastDay(ym);
        List<DailyOccupancy> rows = dailyOccupancyMapper.selectList(
                new LambdaQueryWrapper<DailyOccupancy>().between(DailyOccupancy::getBizDate, from, to));
        Map<LocalDate, DailyOccupancy> byDate = rows.stream()
                .collect(Collectors.toMap(DailyOccupancy::getBizDate, r -> r, (a, b) -> a));
        int enabled = recalcService.enabledRoomCount();
        List<Map<String, Object>> list = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            list.add(dayView(d, byDate.get(d), enabled));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("list", list);
        return out;
    }

    /** 9.2 登记/覆盖某日入住的具体房间（自动建档 + 刷新聚合 + 重算月度）。 */
    @Transactional
    public Map<String, Object> putDayRooms(DayRoomsReq req) {
        if (req.getBizDate() == null) {
            throw BizException.badRequest("bizDate 不能为空");
        }
        if (req.getRoomNos() == null) {
            throw BizException.badRequest("roomNos 不能为空");
        }
        applyDay(req.getBizDate(), req.getRoomNos(), req.getNote());
        String month = Months.format(YearMonth.from(req.getBizDate()));
        recalcService.recalc(month);
        return dayView(req.getBizDate(), dailyOccupancyMapper.selectOne(
                new LambdaQueryWrapper<DailyOccupancy>().eq(DailyOccupancy::getBizDate, req.getBizDate())),
                recalcService.enabledRoomCount());
    }

    /** 9.3 当日已入住房间列表。 */
    public List<Map<String, Object>> dayRooms(LocalDate bizDate) {
        if (bizDate == null) {
            throw BizException.badRequest("bizDate 不能为空");
        }
        Map<Long, Room> byId = roomMapper.selectList(null).stream()
                .collect(Collectors.toMap(Room::getId, r -> r, (a, b) -> a));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long roomId : dailyOccupiedRoomMapper.selectRoomIdsByDate(bizDate)) {
            Room r = byId.get(roomId);
            if (r == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roomId", r.getId());
            m.put("roomNo", r.getRoomNo());
            m.put("roomType", r.getRoomType());
            out.add(m);
        }
        return out;
    }

    /** 9.4 房间 × 日期 入住矩阵（房态页主体）。仅展示启用房间。 */
    public Map<String, Object> matrix(String month) {
        YearMonth ym = Months.require(month);
        LocalDate from = Months.firstDay(ym);
        LocalDate to = Months.lastDay(ym);
        List<Room> rooms = roomMapper.selectList(new LambdaQueryWrapper<Room>()
                .eq(Room::getEnabled, 1)
                .orderByAsc(Room::getSortOrder).orderByAsc(Room::getRoomNo));
        Map<LocalDate, List<Long>> byDate = dailyOccupiedRoomMapper.selectBetween(from, to).stream()
                .collect(Collectors.groupingBy(DailyOccupiedRoom::getBizDate,
                        Collectors.mapping(DailyOccupiedRoom::getRoomId, Collectors.toList())));

        List<Map<String, Object>> roomList = new ArrayList<>();
        for (Room r : rooms) {
            List<String> occupied = new ArrayList<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (byDate.getOrDefault(d, List.of()).contains(r.getId())) {
                    occupied.add(d.toString());
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roomNo", r.getRoomNo());
            m.put("roomType", r.getRoomType());
            m.put("occupied", occupied);
            roomList.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("rooms", roomList);
        out.put("legend", Map.of("occupied", true));
        return out;
    }

    /** 9.5 批量逐日补录。 */
    @Transactional
    public Map<String, Object> batch(BatchReq req) {
        if (req.getRows() == null || req.getRows().isEmpty()) {
            throw BizException.badRequest("rows 不能为空");
        }
        for (DayRoomsReq row : req.getRows()) {
            if (row.getBizDate() == null || row.getRoomNos() == null) {
                throw BizException.badRequest("每行需 bizDate + roomNos");
            }
        }
        String month = null;
        for (DayRoomsReq row : req.getRows()) {
            applyDay(row.getBizDate(), row.getRoomNos(), row.getNote());
            month = month == null ? Months.format(YearMonth.from(row.getBizDate())) : month;
        }
        recalcService.recalc(month);
        return daily(month);
    }

    /** 9.6 对账差异（同 5.5，房态详页版）。 */
    public ReconcileInfo reconcile(String month) {
        return recalcService.reconcile(month);
    }

    /**
     * 9.7 工作日/周末拆分入住率。
     * 分母口径与 RecalcService 的 occupancy_rate 一致：仅计「当月有房态记录的营业日 × 可售房间」，
     * 而非全月日历日——避免月末/月初空档稀释，也防止周末"间夜少/分母大"导致出现 < 平日的失真结果。
     */
    public Map<String, Object> workdayRate(String month) {
        YearMonth ym = Months.require(month);
        LocalDate from = Months.firstDay(ym);
        LocalDate to = Months.lastDay(ym);
        int enabled = recalcService.enabledRoomCount();
        int wdN = 0, weN = 0, wdCount = 0, weCount = 0;
        for (DailyOccupancy r : dailyOccupancyMapper.selectList(
                new LambdaQueryWrapper<DailyOccupancy>().between(DailyOccupancy::getBizDate, from, to))) {
            int n = r.getOccupiedRooms() == null ? 0 : r.getOccupiedRooms();
            if (HolidayUtil.isWeekend(r.getBizDate())) {
                weN += n;
                weCount++;
            } else {
                wdN += n;
                wdCount++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month);
        out.put("workdayRate", rateOf(wdN, enabled, wdCount));
        out.put("weekendRate", rateOf(weN, enabled, weCount));
        out.put("workdayNights", wdN);
        out.put("weekendNights", weN);
        out.put("workdayDays", wdCount);
        out.put("weekendDays", weCount);
        return out;
    }

    /** 覆盖式写入某日明细 + 刷新当日聚合（空数组也落 0 行）。不触发月度重算（调用方统一触发）。 */
    private void applyDay(LocalDate d, List<String> rawNos, String note) {
        List<String> nos = rawNos.stream()
                .map(String::trim).filter(s -> !s.isBlank()).distinct().collect(Collectors.toList());
        dailyOccupiedRoomMapper.deleteByBizDate(d);
        for (String no : nos) {
            Room r = roomService.findOrCreateRoom(no, null, null);
            dailyOccupiedRoomMapper.insertRow(d, r.getId());
        }
        upsertDaily(d, nos.size(), note);
        audit.log("PUT_DAY_ROOMS", "bizDate=" + d + " rooms=" + nos);
    }

    /** 刷新单日聚合行（total_rooms=当前可售快照；occupied=明细计数）。 */
    private void upsertDaily(LocalDate d, int occupied, String note) {
        DailyOccupancy row = dailyOccupancyMapper.selectOne(
                new LambdaQueryWrapper<DailyOccupancy>().eq(DailyOccupancy::getBizDate, d));
        boolean isNew = row == null;
        if (isNew) {
            row = new DailyOccupancy();
            row.setBizDate(d);
            row.setSource("manual");
        }
        row.setOccupiedRooms(occupied);
        row.setTotalRooms(recalcService.enabledRoomCount());
        if (note != null) {
            row.setNote(note);
        }
        if (isNew) {
            dailyOccupancyMapper.insert(row);
        } else {
            dailyOccupancyMapper.updateById(row);
        }
    }

    /** 单日聚合视图（9.1 list 项）。 */
    private Map<String, Object> dayView(LocalDate d, DailyOccupancy row, int enabled) {
        List<String> roomNos = dailyOccupiedRoomMapper.selectRoomIdsByDate(d).stream()
                .map(id -> roomMapper.selectById(id))
                .filter(Objects::nonNull)
                .map(Room::getRoomNo)
                .collect(Collectors.toList());
        int occupied = row == null ? 0 : (row.getOccupiedRooms() == null ? 0 : row.getOccupiedRooms());
        int total = row == null ? Math.max(enabled, roomNos.size()) : (row.getTotalRooms() == null ? enabled : row.getTotalRooms());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bizDate", d.toString());
        m.put("occupiedRooms", occupied);
        m.put("totalRooms", total);
        m.put("occupiedRoomNos", roomNos);
        m.put("occupancyRate", total == 0 ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(occupied * 100.0 / total).setScale(2, RoundingMode.HALF_UP));
        m.put("isWeekend", HolidayUtil.isWeekend(d));
        m.put("isHoliday", HolidayUtil.isHoliday(d));
        m.put("source", row == null || row.getSource() == null ? "manual" : row.getSource());
        m.put("note", row == null ? null : row.getNote());
        return m;
    }

    private static BigDecimal rateOf(int nights, int enabled, int days) {
        if (enabled <= 0 || days <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(nights * 100.0 / (enabled * days)).setScale(2, RoundingMode.HALF_UP);
    }

    public static class DayRoomsReq {
        private LocalDate bizDate;
        private List<String> roomNos;
        private String note;

        public LocalDate getBizDate() {
            return bizDate;
        }

        public void setBizDate(LocalDate bizDate) {
            this.bizDate = bizDate;
        }

        public List<String> getRoomNos() {
            return roomNos;
        }

        public void setRoomNos(List<String> roomNos) {
            this.roomNos = roomNos;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    public static class BatchReq {
        private List<DayRoomsReq> rows;

        public List<DayRoomsReq> getRows() {
            return rows;
        }

        public void setRows(List<DayRoomsReq> rows) {
            this.rows = rows;
        }
    }
}
