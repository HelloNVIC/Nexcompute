package com.nexcompute.management.controller;

import com.nexcompute.management.common.ApiResponse;
import com.nexcompute.management.domain.Ticket;
import com.nexcompute.management.domain.TicketHistory;
import com.nexcompute.management.domain.TicketType;
import com.nexcompute.management.security.RequirePermission;
import com.nexcompute.management.service.TicketService;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工单接口（任务 12.2、12.3、12.4）
 */
@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
@RequirePermission(module = "ticket", action = RequirePermission.Action.VIEW)
public class TicketController {

    private final TicketService ticketService;

    @GetMapping
    public ApiResponse<List<Ticket>> list() {
        return ApiResponse.success(ticketService.listVisible());
    }

    @GetMapping("/{id}")
    public ApiResponse<Ticket> get(@PathVariable Long id) {
        return ApiResponse.success(ticketService.getTicket(id));
    }

    /** 学生提交工单（任务 12.2；D11：联系方式） */
    @PostMapping
    @RequirePermission(module = "ticket", action = RequirePermission.Action.EDIT)
    public ApiResponse<Ticket> create(@Valid @RequestBody CreateTicketRequest request) {
        return ApiResponse.success(ticketService.createTicket(
                request.getTitle(), request.getType(), request.getContent(), request.getContact()));
    }

    /** 管理员回复并关闭（任务 12.4） */
    @PostMapping("/{id}/close")
    @RequirePermission(module = "ticket", action = RequirePermission.Action.EDIT)
    public ApiResponse<Ticket> close(@PathVariable Long id, @RequestBody CloseTicketRequest request) {
        return ApiResponse.success(ticketService.closeTicket(id, request.getReply()));
    }

    /** 提交人撤销工单（platform-refinements #6） */
    @DeleteMapping("/{id}")
    @RequirePermission(module = "ticket", action = RequirePermission.Action.DELETE)
    public ApiResponse<Void> cancel(@PathVariable Long id) {
        ticketService.cancelTicket(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/history")
    public ApiResponse<List<TicketHistory>> history(@PathVariable Long id) {
        return ApiResponse.success(ticketService.getHistory(id));
    }

    @Data
    public static class CreateTicketRequest {
        private String title;
        private TicketType type;
        private String content;
        private String contact;  // D11：联系方式（默认账户手机号）
    }

    @Data
    public static class CloseTicketRequest {
        private String reply;
    }
}
