package com.nexcompute.management.service;

import com.nexcompute.management.audit.Audited;
import com.nexcompute.management.common.BusinessException;
import com.nexcompute.management.common.ErrorCode;
import com.nexcompute.management.domain.*;
import com.nexcompute.management.domain.User;
import com.nexcompute.management.repository.*;
import com.nexcompute.management.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * 工单服务（任务 12.2、12.3、12.4）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository historyRepository;
    private final UserRepository userRepository;
    private final ResearchGroupRepository groupRepository;
    private final NotificationService notificationService;

    /**
     * 学生提交工单（任务 12.2）
     * 五种类型，直接派给管理员
     */
    @Audited(action = "TICKET_CREATE", targetType = "TICKET", targetIdExpr = "#result.id")
    @Transactional
    public Ticket createTicket(String title, TicketType type, String content) {
        Long userId = SecurityUtils.getCurrentUserId();
        User submitter = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        String groupName = null;
        if (submitter.getGroupId() != null) {
            groupName = groupRepository.findById(submitter.getGroupId())
                    .map(ResearchGroup::getName).orElse(null);
        }

        Ticket ticket = Ticket.builder()
                .title(title)
                .type(type)
                .content(content)
                .submitterId(userId)
                .submitterName(submitter.getRealName())
                .groupId(submitter.getGroupId())
                .groupName(groupName)
                .status("PENDING")
                .build();
        ticket = ticketRepository.save(ticket);

        // platform-refinements #3：生成唯一编号 TK{yyyyMMdd}-{6位id}
        String ticketNo = "TK" + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + String.format("%06d", ticket.getId());
        ticket.setTicketNo(ticketNo);
        ticket = ticketRepository.save(ticket);

        // 流转历史
        historyRepository.save(TicketHistory.builder()
                .ticketId(ticket.getId())
                .action("CREATED")
                .operatorId(userId)
                .operatorName(submitter.getRealName())
                .content(content)
                .build());

        log.info("[Ticket] 工单已创建: {} ({} by {})", ticket.getId(), type, submitter.getUsername());
        return ticket;
    }

    /**
     * 工单可见性过滤（任务 12.3）
     * 学生=自己、导师=自己+课题组学生、管理员=全部
     */
    public List<Ticket> listVisible() {
        UserRole role = SecurityUtils.getCurrentRole();
        Long userId = SecurityUtils.getCurrentUserId();

        if (role == UserRole.ADMIN) {
            return ticketRepository.findAll();
        }
        if (role == UserRole.MENTOR) {
            User mentor = userRepository.findById(userId).orElseThrow();
            // platform-refinements #5：按 id 去重（自己提交的可能同时命中 submitterId 与 groupId 两查询）
            Map<Long, Ticket> byId = new LinkedHashMap<>();
            for (Ticket t : ticketRepository.findBySubmitterIdOrderByCreatedAtDesc(userId)) byId.put(t.getId(), t);
            if (mentor.getGroupId() != null) {
                for (Ticket t : ticketRepository.findByGroupIdOrderByCreatedAtDesc(mentor.getGroupId())) {
                    byId.put(t.getId(), t);
                }
            }
            return new ArrayList<>(byId.values());
        }
        // 学生
        return ticketRepository.findBySubmitterIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 管理员处理工单：回复并关闭（任务 12.4）
     * 状态待处理->已关闭，记录流转历史，入审计，触发通知
     */
    @Audited(action = "TICKET_CLOSE", targetType = "TICKET", targetIdExpr = "#id")
    @Transactional
    public Ticket closeTicket(Long id, String reply) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅管理员可处理工单");
        }

        Long adminId = SecurityUtils.getCurrentUserId();
        User admin = userRepository.findById(adminId).orElseThrow();

        ticket.setStatus("CLOSED");
        ticket.setReply(reply);
        ticket.setReplierId(adminId);
        ticket.setReplierName(admin.getRealName());
        ticket.setRepliedAt(Instant.now());
        ticket = ticketRepository.save(ticket);

        // 流转历史
        historyRepository.save(TicketHistory.builder()
                .ticketId(ticket.getId())
                .action("CLOSED")
                .operatorId(adminId)
                .operatorName(admin.getRealName())
                .content(reply)
                .build());

        // 触发未读消息通知给提交学生（任务 12.4 / 13.7）
        notificationService.notify(ticket.getSubmitterId(),
                NotificationType.TICKET, ticket.getId(),
                "工单已回复：" + ticket.getTitle(),
                "管理员回复：" + reply);

        log.info("[Ticket] 工单已关闭: {} (by {})", ticket.getId(), admin.getUsername());
        return ticket;
    }

    public Ticket getTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
    }

    /**
     * 提交人撤销工单（platform-refinements #6）：仅 PENDING 且本人可撤销。
     */
    @Audited(action = "TICKET_CANCEL", targetType = "TICKET", targetIdExpr = "#id")
    @Transactional
    public void cancelTicket(Long id) {
        Ticket ticket = getTicket(id);
        Long userId = SecurityUtils.getCurrentUserId();
        if (SecurityUtils.getCurrentRole() != UserRole.ADMIN
                && !ticket.getSubmitterId().equals(userId)) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED, "仅提交人可撤销");
        }
        if (!"PENDING".equals(ticket.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "仅待处理工单可撤销");
        }
        ticket.setStatus("CANCELLED");
        ticketRepository.save(ticket);
        historyRepository.save(TicketHistory.builder()
                .ticketId(ticket.getId())
                .action("CANCELLED")
                .operatorId(userId)
                .operatorName(ticket.getSubmitterName())
                .content("提交人撤销")
                .build());
        log.info("[Ticket] 工单已撤销: {}", ticket.getId());
    }

    public List<TicketHistory> getHistory(Long ticketId) {
        return historyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }
}
