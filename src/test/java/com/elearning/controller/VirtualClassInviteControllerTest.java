package com.elearning.controller;

import com.elearning.entity.*;
import com.elearning.repository.UserRepository;
import com.elearning.repository.VirtualClassAttendanceRepository;
import com.elearning.repository.VirtualClassInviteRepository;
import com.elearning.repository.VirtualClassRepository;
import com.elearning.service.JitsiTokenService;
import com.elearning.service.VirtualClassInviteService;
import com.elearning.service.VirtualClassRollCallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VirtualClassInviteControllerTest {

    private final VirtualClassInviteRepository invites = mock(VirtualClassInviteRepository.class);
    private final VirtualClassAttendanceRepository attendances = mock(VirtualClassAttendanceRepository.class);
    private final JitsiTokenService jitsi = mock(JitsiTokenService.class);
    private final VirtualClassInviteController controller = new VirtualClassInviteController(
        mock(VirtualClassRepository.class), invites, attendances, mock(UserRepository.class),
        mock(VirtualClassInviteService.class), mock(VirtualClassRollCallService.class), jitsi);

    private VirtualClass vc;
    private final List<VirtualClassAttendance> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        vc = VirtualClass.builder().id(7L).title("Comptabilité").roomName("elearning-abc").status("ONGOING").build();
        when(attendances.findByVirtualClassOrderByJoinedAtAsc(any())).thenAnswer(i -> saved);
        when(attendances.save(any())).thenAnswer(i -> {
            VirtualClassAttendance a = i.getArgument(0);
            a.setId((long) saved.size() + 1);
            saved.add(a);
            return a;
        });
        when(attendances.findById(anyLong())).thenAnswer(i -> saved.stream().filter(a -> a.getId().equals(i.getArgument(0))).findFirst());
        when(jitsi.createGuestToken(any(), any(), any(), any())).thenReturn("jwt");
    }

    private VirtualClassInvite invite(boolean shared) {
        VirtualClassInvite inv = VirtualClassInvite.builder().id(3L).virtualClass(vc).token("tok").shared(shared)
            .name(shared ? null : "Awa Diop").email(shared ? null : "awa@test.com").build();
        when(invites.findByToken("tok")).thenReturn(Optional.of(inv));
        return inv;
    }

    private static VirtualClassInviteController.JoinInput input(String name, String session) {
        VirtualClassInviteController.JoinInput in = new VirtualClassInviteController.JoinInput();
        in.setName(name);
        in.setSession(session);
        return in;
    }

    @Test
    void sharedLinkGuestJoinsWithTypedNameAndCountsAsAttendance() {
        invite(true);
        ResponseEntity<?> r = controller.join("tok", input("  Moussa   Fall ", "session-12345678"));
        assertEquals(200, r.getStatusCode().value());
        assertEquals("Moussa Fall (invité)", ((Map<?, ?>) r.getBody()).get("displayName"));
        VirtualClassAttendance a = saved.get(0);
        assertNull(a.getUser());
        assertEquals(Role.ROLE_STUDENT, a.getRole());
        assertTrue(VirtualClassInviteService.isSharedGuestEmail(a.getEmail()));
        verify(jitsi).createGuestToken(eq("elearning-abc"), eq("guest-session-12345678"), eq("Moussa Fall (invité)"), any());
    }

    @Test
    void sharedLinkGuestMustGiveANameAndABrowserSession() {
        invite(true);
        assertEquals(400, controller.join("tok", input(" ", "session-12345678")).getStatusCode().value());
        assertEquals(400, controller.join("tok", input("Awa Diop", "bad")).getStatusCode().value());
        assertTrue(saved.isEmpty());
    }

    @Test
    void personalInviteUsesTheInvitedPersonsIdentity() {
        invite(false);
        ResponseEntity<?> r = controller.join("tok", input("Someone Else", null));
        assertEquals(200, r.getStatusCode().value());
        assertEquals("awa@test.com", saved.get(0).getEmail());
        assertEquals("Awa Diop", saved.get(0).getFullName());
    }

    @Test
    void cannotJoinBeforeTheTeacherStartsOrWhenTheLinkIsDisabled() {
        VirtualClassInvite inv = invite(true);
        vc.setStatus("SCHEDULED");
        assertEquals(409, controller.join("tok", input("Awa Diop", "session-12345678")).getStatusCode().value());
        vc.setStatus("ONGOING");
        inv.setActive(false);
        assertEquals(409, controller.join("tok", input("Awa Diop", "session-12345678")).getStatusCode().value());
        assertTrue(saved.isEmpty());
    }

    @Test
    void anotherBrowserCannotSendHeartbeatsForAGuest() {
        invite(true);
        controller.join("tok", input("Awa Diop", "session-12345678"));
        assertEquals(200, controller.heartbeat("tok", 1L, "session-12345678").getStatusCode().value());
        assertEquals(403, controller.heartbeat("tok", 1L, "session-87654321").getStatusCode().value());
    }
}
