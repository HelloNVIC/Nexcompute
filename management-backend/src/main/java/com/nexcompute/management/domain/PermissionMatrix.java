package com.nexcompute.management.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "permission_matrix")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PermissionMatrix {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "module_id", nullable = false)
    private Long moduleId;

    @Column(name = "can_view", nullable = false)
    private Boolean canView;

    @Column(name = "can_edit", nullable = false)
    private Boolean canEdit;

    @Column(name = "can_delete", nullable = false)
    private Boolean canDelete;
}
