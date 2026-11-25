package com.elearning.service;

import com.elearning.entity.VirtualClass;
import com.elearning.entity.VirtualClassInvite;
import com.elearning.repository.VirtualClassInviteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Accès aux classes virtuelles sans compte : lien partagé de la séance et invitations personnelles par email.
 * Un invité est identifié dans les présences et les appels par une adresse : celle de son invitation,
 * ou, pour le lien partagé, une adresse propre à son navigateur ({@link #sharedGuestEmail}).
 */
@Service
@RequiredArgsConstructor
public class VirtualClassInviteService {

    /** Domaine des adresses attribuées aux personnes entrées par le lien partagé (aucun email n'y est envoyé). */
    public static final String SHARED_GUEST_DOMAIN = "@lien.invite";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final VirtualClassInviteRepository inviteRepository;
    private final EmailService emailService;
    private final EmailDomainChecker emailDomainChecker;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    public record GuestInput(String name, String email) {}
    public record Failure(String email, String name, String reason) {}
    public record AddResult(List<VirtualClassInvite> added, List<Failure> skipped, List<Failure> emailFailed) {}

    public String link(VirtualClassInvite invite) {
        String base = frontendUrl.endsWith("/") ? frontendUrl.substring(0, frontendUrl.length() - 1) : frontendUrl;
        return base + "/classe/" + invite.getToken();
    }

    public Optional<VirtualClassInvite> sharedLink(VirtualClass vc) {
        return vc.getInvites().stream().filter(VirtualClassInvite::isShared).findFirst();
    }

    /** Crée le lien partagé s'il manque, ou en change l'adresse (l'ancienne n'ouvre plus la séance), puis l'active. */
    public VirtualClassInvite enableSharedLink(VirtualClass vc, boolean regenerate) {
        VirtualClassInvite link = sharedLink(vc).orElse(null);
        if (link == null) {
            link = VirtualClassInvite.builder().virtualClass(vc).shared(true).token(newToken())
                .createdAt(LocalDateTime.now()).build();
            vc.getInvites().add(link);
        } else if (regenerate) {
            link.setToken(newToken());
        }
        link.setActive(true);
        return inviteRepository.save(link);
    }

    /**
     * Redonne à la séance un ancien lien partagé (adresse complète ou seulement son jeton), par exemple déjà
     * envoyé sur WhatsApp avant que sa séance soit supprimée ou que le lien soit remplacé.
     */
    public VirtualClassInvite restoreSharedLink(VirtualClass vc, String linkOrToken) {
        String value = linkOrToken == null ? "" : linkOrToken.trim();
        int q = value.indexOf('?');
        if (q >= 0) value = value.substring(0, q);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        String token = value.substring(value.lastIndexOf('/') + 1);
        if (!token.matches("[A-Za-z0-9_-]{12,64}")) {
            throw new IllegalArgumentException("Adresse non reconnue : collez le lien complet, par exemple …/classe/AbC123….");
        }
        VirtualClassInvite owner = inviteRepository.findByToken(token).orElse(null);
        if (owner != null && !owner.getVirtualClass().getId().equals(vc.getId())) {
            throw new IllegalArgumentException("Ce lien est déjà utilisé par la séance « " + owner.getVirtualClass().getTitle() + " ».");
        }
        if (owner != null && !owner.isShared()) {
            throw new IllegalArgumentException("Ce lien est une invitation personnelle de cette séance, pas son lien partagé.");
        }
        VirtualClassInvite link = enableSharedLink(vc, false);
        link.setToken(token);
        link.setActive(true);
        return inviteRepository.save(link);
    }

    public void disableSharedLink(VirtualClass vc) {
        sharedLink(vc).ifPresent(l -> l.setActive(false));
    }

    /**
     * Ajoute des invités personnels et leur envoie le lien. Les adresses invalides et celles déjà invitées
     * sont écartées (et citées), un échec d'envoi n'empêche pas l'invitation d'exister.
     */
    public AddResult addGuests(VirtualClass vc, Collection<GuestInput> guests) {
        Set<String> already = new HashSet<>();
        vc.getInvites().stream().filter(i -> !i.isShared() && i.getEmail() != null)
            .forEach(i -> already.add(i.getEmail().toLowerCase()));
        List<VirtualClassInvite> added = new ArrayList<>();
        List<Failure> skipped = new ArrayList<>();
        for (GuestInput g : guests) {
            String rawEmail = g.email() == null ? "" : g.email().trim();
            String name = g.name() == null || g.name().isBlank() ? rawEmail : g.name().trim();
            String email;
            try {
                email = emailDomainChecker.check(rawEmail).toLowerCase();
            } catch (IllegalArgumentException e) {
                skipped.add(new Failure(rawEmail, name, e.getMessage()));
                continue;
            }
            if (!already.add(email)) {
                skipped.add(new Failure(email, name, "déjà invité(e)"));
                continue;
            }
            VirtualClassInvite invite = inviteRepository.save(VirtualClassInvite.builder()
                .virtualClass(vc).shared(false).token(newToken()).name(name).email(email)
                .createdAt(LocalDateTime.now()).build());
            vc.getInvites().add(invite);
            added.add(invite);
        }
        List<Failure> emailFailed = new ArrayList<>();
        for (VirtualClassInvite invite : added) {
            String error = sendInvite(invite);
            if (error != null) emailFailed.add(new Failure(invite.getEmail(), invite.getName(), error));
        }
        return new AddResult(added, skipped, emailFailed);
    }

    /** Envoie (ou renvoie) le lien personnel ; retourne la raison de l'échec, ou null. */
    public String sendInvite(VirtualClassInvite invite) {
        VirtualClass vc = invite.getVirtualClass();
        String teacher = vc.getTeacher() == null ? null : StudentAudienceService.fullName(vc.getTeacher());
        String error = emailService.sendVirtualClassInvite(invite.getEmail(), invite.getName(), vc.getTitle(), teacher,
            vc.getScheduledAt(), vc.getDurationMinutes(), link(invite));
        if (error == null) invite.setEmailSentAt(LocalDateTime.now());
        return error;
    }

    /** Adresse d'un participant entré par le lien partagé, propre à son navigateur. */
    public static String sharedGuestEmail(String session) {
        return "invite-" + session.toLowerCase(Locale.ROOT) + SHARED_GUEST_DOMAIN;
    }

    public static boolean isSharedGuestEmail(String email) {
        return email != null && email.toLowerCase(Locale.ROOT).endsWith(SHARED_GUEST_DOMAIN);
    }

    public static boolean validSession(String session) {
        return session != null && session.matches("[A-Za-z0-9-]{8,64}");
    }

    private static String newToken() {
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < 24; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
