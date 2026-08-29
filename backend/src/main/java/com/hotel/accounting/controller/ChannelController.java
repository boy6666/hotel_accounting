package com.hotel.accounting.controller;

import com.hotel.accounting.common.ApiResult;
import com.hotel.accounting.model.Channel;
import com.hotel.accounting.service.ChannelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 渠道（03 §7）：字典 CRUD（佣金/停用/新增）+ 渠道×月统计 + 趋势 + 手录修正。
 */
@RestController
@RequestMapping("/api")
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping("/channels")
    public ApiResult<List<Channel>> list(@RequestParam(required = false) String type,
                                         @RequestParam(required = false) Integer enabled) {
        return ApiResult.ok(channelService.listChannels(type, enabled));
    }

    @PostMapping("/channels")
    public ApiResult<Channel> create(@RequestBody ChannelService.ChannelReq req) {
        return ApiResult.ok(channelService.createChannel(req));
    }

    @PutMapping("/channels/{id}")
    public ApiResult<Channel> update(@PathVariable Long id, @RequestBody ChannelService.ChannelReq req) {
        return ApiResult.ok(channelService.updateChannel(id, req));
    }

    @GetMapping("/channel-monthly")
    public ApiResult<Map<String, Object>> monthly(@RequestParam String month) {
        return ApiResult.ok(channelService.channelMonthlyList(month));
    }

    @GetMapping("/channel-monthly/trend")
    public ApiResult<List<Map<String, Object>>> trend(@RequestParam String from, @RequestParam String to) {
        return ApiResult.ok(channelService.channelTrend(from, to));
    }
}
