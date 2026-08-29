package com.hotel.accounting.service;

import lombok.Data;

import java.util.List;

/**
 * 对账结果。房夜（daily_occupancy 合计） vs 间夜（channel_monthly 合计）。
 */
@Data
public class ReconcileInfo {

    /** YYYY-MM */
    private String month;
    /** matched / diff / unchecked */
    private String status;
    /** Σ daily_occupancy.occupied_rooms */
    private Integer occupancyNights;
    /** Σ channel_monthly.nights */
    private Integer channelNights;
    /** 房夜 − 间夜 */
    private Integer diff;
    /** diff≠0 时渠道维排查 */
    private List<ChannelDiff> detailChannels = List.of();

    @Data
    public static class ChannelDiff {
        private String channelName;
        private Integer reportedNights;
        private Integer actualRoomNights;
        private Integer diff;
    }
}
