package com.elearning.controller;

import com.elearning.entity.Role;
import com.elearning.entity.User;
import com.elearning.entity.VirtualClass;
import com.elearning.entity.VirtualClassAttendance;
import com.elearning.entity.VirtualClassInvite;
import com.elearning.repository.UserRepository;
import com.elearning.repository.VirtualClassAttendanceRepository;
import com.elearning.repository.VirtualClassInviteRepository;
import com.elearning.repository.VirtualClassRepository;
import com.elearning.service.JitsiTokenService;
import com.elearning.service.StudentAudienceService;
import com.elearning.service.VirtualClassInviteService;
import com.elearning.service.VirtualClassRollCallService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Classes virtuelles sans compte.
 * Professeur : lien partagé de la séance et invitations personnelles par email.
 * Invité (public, /api/public/…) : entrée dans la salle, présence et réponse aux appels, avec le seul lien.
 */
@RestController
@RequiredArgsConstructor
public class VirtualClassInviteController {

    private final VirtualClassRepository virtualClassRepository;
    private final VirtualClassInviteRepository inviteRepository;
    private final VirtualClassAttendanceRepository attendanceRepository;
    private final UserRepository userRepository;
    private final VirtualClassInviteService inviteService;
    private final VirtualClassRollCallService rollCallService;
    private final JitsiTokenService jitsiTokenService;

    // ── Professeur ────────────────────────────────────────────────────────

    @Data static class GuestDto { Long id; String name; String email; String link; boolean active; String emailSentAt; }
    @Data static class InvitesDto { String sharedLink; boolean sharedLinkActive; List<GuestDto> guests; }
    @Data static class GuestListInput { List<VirtualClassInviteService.GuestInput> guests; }

    @GetMapping("/api/teacher/virtual-classes/{id}/invites")
    @Transactional(readOnly = true)
    public ResponseEntity<?> invites(@PathVariable Long id, Authentication auth) {
        VirtualClass vc = hostedClass(id, auth);
        if (vc == null) return forbidden();
        return ResponseEntity.ok(invitesDto(vc));
    }

    /** Active le lien partagé (le crée au besoin) ; regenerate=true lui donne une nouvelle adresse. */
    @PostMapping("/api/teacher/virtual-classes/{id}/invites/link")
    @Transactional
    public ResponseEntity<?> enableLink(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean regenerate,
                                        Authentication auth) {
        VirtualClass vc = hostedClass(id, auth);
        if (vc == null) return forbidden();
        inviteService.enableSharedLink(vc, regenerate);
        return ResponseEntity.ok(invitesDto(vc));
    }

    @DeleteMapping("/api/teacher/virtual-classes/{id}/invites/link")
    @Transactional
    public ResponseEntity<?> disableLink(@PathVariable Long id, Authentication auth) {
        VirtualClass vc = hostedClass(id, auth);
        if (vc == null) return forbidden();
        inviteService.disableSharedLink(vc);
        return ResponseEntity.ok(invitesDto(vc));
    }

    /** Invités personnels (nom, email) : chacun reçoit son lien par email. */
    @PostMapping("/api/teacher/virtual-classes/{id}/invites/guests")
    @Transactional
    public ResponseEntity<?> addGuests(@PathVariable Long id, @RequestBody GuestListInput input, Authentication auth) {
        VirtualClass vc = hostedClass(id, auth);
        if (vc == null) return forbidden();
        if (input.guests == null || input.guests.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Ajoutez au moins une personne (nom et email)."));
        }
        var result = inviteService.addGuests(vc, input.guests);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("invites", invitesDto(vc));
        body.put("added", result.added().size());
        body.put("skipped", result.skipped());
        body.put("emailFailed", result.emailFailed());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/teacher/virtual-classes/{id}/invites/guests/{inviteId}/resend")
    @Transactional
    public ResponseEntity<?> resend(@PathVariable Long id, @PathVariable Long inviteId, Authentication auth) {
        VirtualClass vc = hostedClass(id, auth);
        if (vc == null) return forbidden();
        VirtualClassInvite invite = guestOf(vc, inviteId);
        if (invite == null) return ResponseEntity.notFound().build();
        String error = inviteService.sendInvite(invite);
        if (error != null) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", "Email non envoyé : " + error));
        return ResponseEntity.ok(invitesDto(vc));
    }

    @DeleteMapping("/api/teacher/virtual-classes/{id}/invites/guests/{inviteId}")
    @Transactional
    public ResponseEntity<?> removeGuest(@PathVariable Long id, @PathVariable Long inviteId, Authentication auth) {
        VirtualClass vc = hostedClass(id, auth);
        if (vc == null) return forbidden();
        VirtualClassInvite invite = guestOf(vc, inviteId);
        if (invite == null) return ResponseEntity.notFound().build();
        vc.getInvites().remove(invite);
        return ResponseEntity.ok(invitesDto(vc));
    }

    private InvitesDto invitesDto(VirtualClass vc) {
        InvitesDto d = new InvitesDto();
        inviteService.sharedLink(vc).ifPresent(l -> { d.sharedLink = inviteService.link(l); d.sharedLinkActive = l.isActive(); });
        d.guests = vc.getInvites().stream().filter(i -> !i.isShared())
            .sorted(Comparator.comparing(i -> i.getName() == null ? "" : i.getName().toLowerCase()))
            .map(i -> {
                GuestDto g = new GuestDto();
                g.id = i.getId(); g.name = i.getName(); g.email = i.getEmail(); g.link = inviteService.link(i);
                g.active = i.isActive(); g.emailSentAt = i.getEmailSentAt() == null ? null : i.getEmailSentAt().toString();
                return g;
            }).toList();
        return d;
    }

    private VirtualClass hostedClass(Long id, Authentication auth) {
        VirtualClass vc = virtualClassRepository.findById(id).orElse(null);
        if (vc == null) return null;
        User user = userRepository.findByEmail(auth.getName()).orElse(null);
        return user != null && VirtualClassAttendanceController.isHost(vc, user) ? vc : null;
    }

    private static VirtualClassInvite guestOf(VirtualClass vc, Long inviteId) {
        return vc.getInvites().stream().filter(i -> !i.isShared() && i.getId().equals(inviteId)).findFirst().orElse(null);
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("message", "Seuls le professeur de la séance et l'administrateur gèrent les invitations."));
    }

    // ── Invité (sans compte) ──────────────────────────────────────────────

    @Data static class InviteInfoDto {
        String title; String description; String scheduledAt; Integer durationMinutes; String teacherName; String status;
        boolean shared; String guestName; boolean canJoin; String message;
    }
    @Data static class JoinInput { String name; String session; }

    @GetMapping("/api/public/virtual-classes/invite/{token}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> inviteInfo(@PathVariable String token) {
        VirtualClassInvite invite = inviteRepository.findByToken(token).orElse(null);
        if (invite == null) return notFound();
        VirtualClass vc = invite.getVirtualClass();
        InviteInfoDto d = new InviteInfoDto();
        d.title = vc.getTitle(); d.description = vc.getDescription();
        d.scheduledAt = vc.getScheduledAt() == null ? null : vc.getScheduledAt().toString();
        d.durationMinutes = vc.getDurationMinutes();
        d.teacherName = vc.getTeacher() == null ? null : StudentAudienceService.fullName(vc.getTeacher());
        d.status = vc.getStatus();
        d.shared = invite.isShared();
        d.guestName = invite.isShared() ? null : invite.getName();
        d.message = refusal(invite);
        d.canJoin = d.message == null;
        return ResponseEntity.ok(d);
    }

    /** Entrée dans la salle : jeton de visioconférence (participant, jamais modérateur) et début de présence. */
    @PostMapping("/api/public/virtual-classes/invite/{token}/join")
    @Transactional
    public ResponseEntity<?> join(@PathVariable String token, @RequestBody JoinInput input) {
        VirtualClassInvite invite = inviteRepository.findByToken(token).orElse(null);
        if (invite == null) return notFound();
        String refusal = refusal(invite);
        if (refusal != null) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", refusal));
        Guest guest = guest(invite, input.session, input.name == null ? "" : input.name);
        if (guest == null) {
            return ResponseEntity.badRequest().body(Map.of("message", invite.isShared()
                ? "Indiquez votre prénom et votre nom (2 caractères au moins)." : "Lien invalide."));
        }
        VirtualClass vc = invite.getVirtualClass();
        LocalDateTime now = LocalDateTime.now();
        // Une connexion restée ouverte (onglet fermé brutalement) est clôturée à son dernier signe de vie
        attendanceRepository.findByVirtualClassOrderByJoinedAtAsc(vc).stream()
            .filter(a -> a.getLeftAt() == null && a.getEmail().equalsIgnoreCase(guest.email))
            .forEach(a -> a.setLeftAt(a.getLastSeenAt() != null ? a.getLastSeenAt() : a.getJoinedAt()));
        VirtualClassAttendance attendance = attendanceRepository.save(VirtualClassAttendance.builder()
            .virtualClass(vc).email(guest.email).fullName(guest.name).role(Role.ROLE_STUDENT)
            .joinedAt(now).lastSeenAt(now).build());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", jitsiTokenService.createGuestToken(vc.getRoomName(), guest.id, guest.name, guest.email));
        body.put("domain", jitsiTokenService.getDomain());
        body.put("roomName", jitsiTokenService.getRoomName(vc.getRoomName()));
        body.put("displayName", guest.name);
        body.put("attendanceId", attendance.getId());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/public/virtual-classes/invite/{token}/attendance/{attendanceId}/heartbeat")
    @Transactional
    public ResponseEntity<?> heartbeat(@PathVariable String token, @PathVariable Long attendanceId,
                                       @RequestParam(required = false) String session) {
        VirtualClassAttendance a = ownAttendance(token, attendanceId, session);
        if (a == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (a.getLeftAt() == null) a.setLastSeenAt(LocalDateTime.now());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/api/public/virtual-classes/invite/{token}/attendance/{attendanceId}/leave")
    @Transactional
    public ResponseEntity<?> leave(@PathVariable String token, @PathVariable Long attendanceId,
                                   @RequestParam(required = false) String session) {
        VirtualClassAttendance a = ownAttendance(token, attendanceId, session);
        if (a == null) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (a.getLeftAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            a.setLastSeenAt(now);
            a.setLeftAt(now);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Appel du professeur en cours, auquel cet invité n'a pas encore répondu (204 sinon). */
    @GetMapping("/api/public/virtual-classes/invite/{token}/roll-calls/active")
    @Transactional(readOnly = true)
    public ResponseEntity<?> activeRollCall(@PathVariable String token, @RequestParam(required = false) String session) {
        VirtualClassInvite invite = inviteRepository.findByToken(token).orElse(null);
        if (invite == null || !invite.isActive()) return notFound();
        Guest guest = guest(invite, session, null);
        if (guest == null) return ResponseEntity.noContent().build();
        return rollCallService.activeFor(invite.getVirtualClass(), guest.email).<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/api/public/virtual-classes/invite/{token}/roll-calls/{rollCallId}/answer")
    @Transactional
    public ResponseEntity<?> answerRollCall(@PathVariable String token, @PathVariable Long rollCallId,
                                            @RequestParam(required = false) String session) {
        VirtualClassInvite invite = inviteRepository.findByToken(token).orElse(null);
        if (invite == null || !invite.isActive()) return notFound();
        Guest guest = guest(invite, session, null);
        if (guest == null) return ResponseEntity.badRequest().body(Map.of("message", "Lien invalide."));
        VirtualClass vc = invite.getVirtualClass();
        var call = rollCallService.find(vc, rollCallId).orElse(null);
        if (call == null) return ResponseEntity.notFound().build();
        rollCallService.answer(call, guest.email, guest.name);
        return ResponseEntity.ok(Map.of("message", "Présence confirmée."));
    }

    /** Pourquoi l'invité ne peut pas entrer maintenant ; null s'il le peut. */
    private static String refusal(VirtualClassInvite invite) {
        if (!invite.isActive()) return "Ce lien d'invitation a été désactivé par le professeur.";
        VirtualClass vc = invite.getVirtualClass();
        String status = vc.getStatus();
        if ("ONGOING".equals(status)) return null;
        if ("COMPLETED".equals(status)) return "Cette séance est terminée.";
        if ("CANCELLED".equals(status)) return "Cette séance a été annulée.";
        return "La séance n'a pas encore commencé : cette page s'ouvrira dès que le professeur l'aura démarrée.";
    }

    /** Identité de l'invité : celle de son invitation personnelle, ou celle de son navigateur pour le lien partagé. */
    private record Guest(String id, String email, String name) {}

    private Guest guest(VirtualClassInvite invite, String session, String typedName) {
        if (!invite.isShared()) {
            return new Guest("invite-" + invite.getId(), invite.getEmail().toLowerCase(), invite.getName());
        }
        if (!VirtualClassInviteService.validSession(session)) return null;
        String email = VirtualClassInviteService.sharedGuestEmail(session);
        if (typedName != null) {
            // Entrée : nom saisi par l'invité
            String name = typedName.trim().replaceAll("\\s+", " ");
            if (name.length() < 2 || name.length() > 80) return null;
            return new Guest("guest-" + session, email, name + " (invité)");
        }
        // Présence et appels : nom saisi à l'entrée, repris de sa dernière connexion
        String last = attendanceRepository.findByVirtualClassOrderByJoinedAtAsc(invite.getVirtualClass()).stream()
            .filter(a -> a.getEmail().equalsIgnoreCase(email)).reduce((a, b) -> b)
            .map(VirtualClassAttendance::getFullName).orElse("Invité");
        return new Guest("guest-" + session, email, last);
    }

    private VirtualClassAttendance ownAttendance(String token, Long attendanceId, String session) {
        VirtualClassInvite invite = inviteRepository.findByToken(token).orElse(null);
        if (invite == null) return null;
        Guest guest = guest(invite, session, null);
        if (guest == null) return null;
        return attendanceRepository.findById(attendanceId)
            .filter(a -> a.getVirtualClass().getId().equals(invite.getVirtualClass().getId())
                && a.getEmail().equalsIgnoreCase(guest.email))
            .orElse(null);
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Lien d'invitation introuvable ou expiré."));
    }
}
