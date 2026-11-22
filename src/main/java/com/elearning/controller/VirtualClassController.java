package com.elearning.controller;

import com.elearning.dto.response.VirtualClassResponse;
import com.elearning.entity.Course;
import com.elearning.entity.Role;
import com.elearning.entity.VirtualClass;
import com.elearning.entity.User;
import com.elearning.entity.VirtualClassStudent;
import com.elearning.repository.CourseRepository;
import com.elearning.repository.VirtualClassRepository;
import com.elearning.repository.UserRepository;
import com.elearning.service.StudentListParserService;
import com.elearning.service.JitsiTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class VirtualClassController {

    private final VirtualClassRepository virtualClassRepository;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final StudentListParserService studentListParserService;
    private final JitsiTokenService jitsiTokenService;
    private final com.elearning.service.StudentAudienceService audienceService;
    private final com.elearning.service.RecordingStorageService recordingStorage;
    private final com.elearning.service.VirtualClassInviteService inviteService;

    /** Nombre d'étudiants conviés : liste et niveaux ciblés réunis, sans doublon. */
    private VirtualClassResponse toResponse(VirtualClass vc) {
        VirtualClassResponse r = VirtualClassResponse.fromEntity(vc);
        r.setStudentCount(audienceService.virtualClassAudience(vc).size());
        r.setHasRecording(recordingStorage.playable(vc));
        r.setRecordingLost(recordingStorage.lost(vc));
        r.setRecordingUrl(recordingStorage.playbackUrl(vc));
        r.setRecordingLightUrl(recordingStorage.lightUrl(vc));
        r.setGuestCount((int) vc.getInvites().stream().filter(i -> !i.isShared()).count());
        r.setSharedLinkActive(vc.getInvites().stream().anyMatch(i -> i.isShared() && i.isActive()));
        return r;
    }

    @GetMapping("/api/virtual-classes")
    @Transactional(readOnly = true)
    public ResponseEntity<List<VirtualClassResponse>> getAll(@AuthenticationPrincipal UserDetails userDetails) {
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        // Professeur : ses séances ; étudiant : celles de son niveau ou de sa liste ; administrateur : toutes
        return ResponseEntity.ok(virtualClassRepository.findAll().stream()
            .filter(vc -> VirtualClassAttendanceController.canJoin(vc, user))
            .map(this::toResponse).toList());
    }

    @GetMapping("/api/virtual-classes/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<VirtualClassResponse> getById(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        VirtualClass vc = virtualClassRepository.findById(id).orElseThrow();
        if (!VirtualClassAttendanceController.canJoin(vc, userRepository.findByEmail(userDetails.getUsername()).orElseThrow())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(toResponse(vc));
    }

    @GetMapping("/api/virtual-classes/{id}/jitsi-token")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, String>> getJitsiToken(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        VirtualClass virtualClass = virtualClassRepository.findById(id).orElseThrow();
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        boolean host = VirtualClassAttendanceController.isHost(virtualClass, user);
        boolean assignedStudent = com.elearning.service.StudentAudienceService.isInvited(virtualClass, user);

        if (!host && !assignedStudent) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String displayName = user.getFirstName() + " " + user.getLastName();
        String token = jitsiTokenService.createToken(
            virtualClass.getRoomName(), user.getId(), displayName, user.getEmail(), host);
        return ResponseEntity.ok(Map.of(
            "token", token,
            "domain", jitsiTokenService.getDomain(),
            "roomName", jitsiTokenService.getRoomName(virtualClass.getRoomName())));
    }

    @GetMapping("/api/virtual-classes/{id}/recording")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> getRecording(@PathVariable Long id, @AuthenticationPrincipal UserDetails userDetails) {
        VirtualClass vc = virtualClassRepository.findById(id).orElseThrow();
        if (!VirtualClassAttendanceController.canJoin(vc, userRepository.findByEmail(userDetails.getUsername()).orElseThrow())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // Enregistrement rangé en fichier : lecture en streaming depuis /uploads
        if (recordingStorage.fileAvailable(vc)) {
            return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, recordingStorage.playbackUrl(vc)).build();
        }
        if (vc.getRecordingData() == null || vc.getRecordingData().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        byte[] videoBytes = Base64.getDecoder().decode(vc.getRecordingData());
        String mimeType = vc.getRecordingMimeType() != null ? vc.getRecordingMimeType() : "video/webm";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, mimeType)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename=\"" + (vc.getRecordingFilename() != null ? vc.getRecordingFilename() : "recording.webm") + "\"")
            .body(videoBytes);
    }

    @PostMapping(value = "/api/teacher/virtual-classes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<VirtualClassResponse> create(
            @RequestPart("class") VirtualClass data,
            @RequestPart(value = "studentList", required = false) MultipartFile studentListFile,
            @RequestParam(value = "teacherId", required = false) Long teacherId,
            @AuthenticationPrincipal UserDetails userDetails) {
        // L'administrateur crée la séance et choisit son professeur ; professeur et étudiants
        // y entrent ensuite par le même lien (le professeur, connecté, y est reconnu comme animateur).
        User creator = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        if (creator.getRole() != Role.ROLE_ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        User teacher = teacherId == null ? null : userRepository.findById(teacherId)
            .filter(u -> u.getRole() == Role.ROLE_TEACHER).orElse(null);
        if (teacher == null) throw new IllegalArgumentException("Choisissez le professeur qui anime la séance.");
        // Liste : les personnes qui ont un compte étudiant y sont inscrites, les autres reçoivent une invitation
        // personnelle par email (sans compte). S'y ajoutent les niveaux entiers et, au choix, un lien partagé.
        final List<StudentListParserService.StudentInfo> studentInfos;
        try {
            studentInfos = studentListFile == null || studentListFile.isEmpty()
                ? List.of() : studentListParserService.parseFile(studentListFile);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        List<User> accounts = new ArrayList<>();
        List<com.elearning.service.VirtualClassInviteService.GuestInput> guests = new ArrayList<>();
        for (var info : studentInfos) {
            String email = info.email() == null ? "" : info.email().trim().toLowerCase();
            userRepository.findByEmail(email).filter(u -> u.getRole() == Role.ROLE_STUDENT).ifPresentOrElse(accounts::add,
                () -> guests.add(new com.elearning.service.VirtualClassInviteService.GuestInput(info.name(), info.email())));
        }
        data.setTargetLevels(com.elearning.service.StudentAudienceService.normalizeLevels(
            com.elearning.service.StudentAudienceService.levelList(data.getTargetLevels())));
        data.setTeacher(teacher);
        data.setStatus("SCHEDULED");
        data.setRoomName("elearning-" + UUID.randomUUID());
        data.setRecordingUrl(null);
        if (data.getCourse() != null && data.getCourse().getId() != null) {
            Course course = courseRepository.findById(data.getCourse().getId()).orElse(null);
            data.setCourse(course);
        } else {
            data.setCourse(null);
        }
        if (data.getStudents() == null) {
            data.setStudents(new ArrayList<>());
        } else {
            data.getStudents().clear();
        }
        accounts.stream().distinct()
            .forEach(account -> data.getStudents().add(VirtualClassStudent.builder()
                .virtualClass(data)
                .studentName(com.elearning.service.StudentAudienceService.fullName(account))
                .studentEmail(account.getEmail())
                .build()));
        if (data.getInvites() == null) data.setInvites(new ArrayList<>());
        VirtualClass saved = virtualClassRepository.save(data);
        // Lien unique de la séance, pour le professeur comme pour les étudiants
        inviteService.enableSharedLink(saved, false);
        if (!guests.isEmpty()) inviteService.addGuests(saved, guests);
        return ResponseEntity.ok(toResponse(saved));
    }

    @PutMapping("/api/teacher/virtual-classes/{id}/status")
    @Transactional
    public ResponseEntity<VirtualClassResponse> updateStatus(
            @PathVariable Long id, @RequestParam String status, @AuthenticationPrincipal UserDetails userDetails) {
        VirtualClass vc = virtualClassRepository.findById(id).orElseThrow();
        if (!VirtualClassAttendanceController.isHost(vc, userRepository.findByEmail(userDetails.getUsername()).orElseThrow())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        vc.setStatus(status);
        return ResponseEntity.ok(toResponse(virtualClassRepository.save(vc)));
    }

    /** Enregistrement envoyé en fichier (multipart) : plus de base64, plus de limite de 45 Mo. */
    @PostMapping(value = "/api/teacher/virtual-classes/{id}/recording", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<VirtualClassResponse> uploadRecording(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "thumbnail", required = false) String thumbnailBase64,
            @AuthenticationPrincipal UserDetails userDetails) throws java.io.IOException {
        VirtualClass vc = virtualClassRepository.findById(id).orElseThrow();
        if (!VirtualClassAttendanceController.isHost(vc, userRepository.findByEmail(userDetails.getUsername()).orElseThrow())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        recordingStorage.store(vc, file, thumbnailBase64);
        vc.setStatus("COMPLETED");
        return ResponseEntity.ok(toResponse(virtualClassRepository.save(vc)));
    }

    @DeleteMapping("/api/teacher/virtual-classes/{id}/recording")
    @Transactional
    public ResponseEntity<VirtualClassResponse> deleteRecording(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        VirtualClass vc = virtualClassRepository.findById(id).orElseThrow();
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        boolean isOwner = vc.getTeacher() != null && vc.getTeacher().getId().equals(user.getId());
        boolean isAdmin = userDetails.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isOwner && !isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        recordingStorage.delete(vc);
        vc.setStatus("COMPLETED");
        return ResponseEntity.ok(toResponse(virtualClassRepository.save(vc)));
    }

    @DeleteMapping("/api/teacher/virtual-classes/{id}")
    @Transactional
    public ResponseEntity<Void> deleteClass(
            @PathVariable Long id,
            @AuthenticationPrincipal UserDetails userDetails) {
        VirtualClass vc = virtualClassRepository.findById(id).orElseThrow();
        User user = userRepository.findByEmail(userDetails.getUsername()).orElseThrow();
        boolean isOwner = vc.getTeacher() != null && vc.getTeacher().getId().equals(user.getId());
        boolean isAdmin = userDetails.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!isOwner && !isAdmin) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        recordingStorage.deleteFiles(vc.getRecordingUrl());
        virtualClassRepository.delete(vc);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api/admin/virtual-classes/{id}")
    @Transactional
    public ResponseEntity<Void> adminDeleteClass(@PathVariable Long id) {
        virtualClassRepository.findById(id).ifPresent(vc -> recordingStorage.deleteFiles(vc.getRecordingUrl()));
        virtualClassRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}
