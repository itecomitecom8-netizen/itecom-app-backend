package com.elearning.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Accès à une classe virtuelle sans compte : soit le lien partagé de la séance (un seul, ouvert à toute
 * personne qui l'a, le nom est saisi à l'entrée), soit une invitation personnelle envoyée par email.
 */
@Entity
@Table(name = "virtual_class_invites")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VirtualClassInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "virtual_class_id", nullable = false)
    @ToString.Exclude
    private VirtualClass virtualClass;

    /** Secret de l'adresse /classe/{token}. */
    @Column(nullable = false, unique = true, length = 64)
    private String token;

    /** true : lien partagé de la séance ; false : invitation personnelle. */
    @Column(nullable = false)
    private boolean shared;

    /** Invitation personnelle : nom et email de l'invité. */
    private String name;
    private String email;

    /** Lien désactivé par le professeur : il n'ouvre plus la séance. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    private LocalDateTime createdAt;
    private LocalDateTime emailSentAt;
}
