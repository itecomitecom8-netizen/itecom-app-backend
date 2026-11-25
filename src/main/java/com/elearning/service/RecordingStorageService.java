package com.elearning.service;

import com.elearning.entity.VirtualClass;
import com.elearning.repository.VirtualClassRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

/**
 * Enregistrements des classes virtuelles rangés en fichiers (et non plus en base64 dans la base) :
 * lecture en streaming (démarrage immédiat, avance rapide), versions recompressées plus légères,
 * et listes de séances qui ne chargent plus les vidéos.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecordingStorageService {

    private static final String DIR = "recordings";

    private final FileStorageService fileStorageService;
    private final VideoOptimizationService videoOptimizer;
    private final VirtualClassRepository virtualClassRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * Garder aussi l'ancienne copie base64 en base (défaut) : si le dossier uploads n'est pas sur un volume
     * persistant, le fichier est recréé depuis la base au démarrage. À passer à false une fois le volume en place.
     */
    @Value("${app.recordings.keep-db-copy:true}")
    private boolean keepDbCopy = true;

    public boolean hasRecording(VirtualClass vc) {
        return (vc.getRecordingUrl() != null && !vc.getRecordingUrl().isBlank())
            || (vc.getRecordingData() != null && !vc.getRecordingData().isEmpty());
    }

    /** Lisible : fichier présent sur le disque, ou copie encore en base. */
    public boolean playable(VirtualClass vc) {
        return fileAvailable(vc) || (vc.getRecordingData() != null && !vc.getRecordingData().isEmpty());
    }

    /** Enregistrement fait, mais fichier disparu du disque (redéploiement sans volume) et sans copie en base. */
    public boolean lost(VirtualClass vc) {
        return hasRecording(vc) && !playable(vc);
    }

    /** Le fichier de l'enregistrement est-il présent sur le disque ? */
    public boolean fileAvailable(VirtualClass vc) {
        return fileStorageService.exists(vc.getRecordingUrl());
    }

    /** Adresse de lecture : version optimisée si prête, sinon l'original (ou l'ancien point d'accès base64). */
    public String playbackUrl(VirtualClass vc) {
        if (fileAvailable(vc)) return videoOptimizer.bestUrl(vc.getRecordingUrl());
        return playable(vc) ? "/api/virtual-classes/" + vc.getId() + "/recording" : null;
    }

    public String lightUrl(VirtualClass vc) {
        return videoOptimizer.lightUrl(vc.getRecordingUrl());
    }

    /** Remplace l'enregistrement de la séance par le fichier envoyé. */
    public void store(VirtualClass vc, MultipartFile file, String thumbnailBase64) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Enregistrement vide.");
        String previous = vc.getRecordingUrl();
        String url = fileStorageService.store(file, DIR);
        vc.setRecordingUrl(url);
        vc.setRecordingData(null);
        vc.setRecordingMimeType(file.getContentType() != null ? file.getContentType() : "video/webm");
        vc.setRecordingFilename(file.getOriginalFilename() != null ? file.getOriginalFilename() : "enregistrement.webm");
        if (thumbnailBase64 != null && !thumbnailBase64.isBlank()) vc.setThumbnailData(thumbnailBase64);
        deleteFiles(previous);
        videoOptimizer.optimizeLater(url);
    }

    /** Prochain morceau attendu par séance (les morceaux arrivent dans l'ordre, un renvoi est ignoré). */
    private final java.util.Map<Long, Integer> nextChunk = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Morceau d'enregistrement envoyé pendant le cours : le morceau 0 ouvre un nouveau fichier (qui remplace
     * l'ancien enregistrement), les suivants s'y ajoutent. Le fichier est lisible à tout moment, même si le
     * professeur ferme l'onglet sans arrêter l'enregistrement.
     * @return false si le morceau arrive dans le désordre (un morceau précédent manque)
     */
    public synchronized boolean appendChunk(VirtualClass vc, int seq, byte[] data, String mimeType) throws IOException {
        if (data == null || data.length == 0) return true;
        if (seq == 0) {
            String previous = vc.getRecordingUrl();
            String url = fileStorageService.storeBytes(data, DIR, ".webm");
            vc.setRecordingUrl(url);
            vc.setRecordingData(null);
            vc.setRecordingMimeType(mimeType != null && !mimeType.isBlank() ? mimeType : "video/webm");
            vc.setRecordingFilename("session-" + vc.getId() + ".webm");
            deleteFiles(previous);
            nextChunk.put(vc.getId(), 1);
            return true;
        }
        Integer expected = nextChunk.get(vc.getId());
        if (expected != null && seq < expected) return true;   // déjà reçu (renvoi après une coupure réseau)
        if (expected != null && seq > expected) return false;  // un morceau manque
        if (!fileAvailable(vc)) return false;
        fileStorageService.append(vc.getRecordingUrl(), data);
        nextChunk.put(vc.getId(), seq + 1);
        return true;
    }

    /** Fin de l'enregistrement envoyé par morceaux : miniature, puis versions recompressées. */
    public void finishChunks(VirtualClass vc, String thumbnailBase64) {
        nextChunk.remove(vc.getId());
        if (thumbnailBase64 != null && !thumbnailBase64.isBlank()) vc.setThumbnailData(thumbnailBase64);
        String url = vc.getRecordingUrl();
        if (url == null) return;
        // Des versions faites sur un fichier encore incomplet (redémarrage pendant le cours) seraient tronquées
        videoOptimizer.deleteVariants(url);
        videoOptimizer.optimizeLater(url);
    }

    /** Lance la recompression de l'enregistrement s'il n'est pas déjà fait. */
    public void optimize(VirtualClass vc) {
        if (fileAvailable(vc)) videoOptimizer.optimizeLater(vc.getRecordingUrl());
    }

    public void delete(VirtualClass vc) {
        nextChunk.remove(vc.getId());
        deleteFiles(vc.getRecordingUrl());
        vc.setRecordingUrl(null);
        vc.setRecordingData(null);
        vc.setThumbnailData(null);
        vc.setRecordingMimeType(null);
        vc.setRecordingFilename(null);
    }

    public void deleteFiles(String url) {
        if (url == null) return;
        videoOptimizer.deleteVariants(url);
        fileStorageService.delete(url);
    }

    /**
     * Au démarrage : les enregistrements stockés en base64 sont écrits en fichiers, un par un (pour ne pas saturer
     * la mémoire). Un fichier disparu (redéploiement) est recréé. La copie en base n'est vidée que si
     * app.recordings.keep-db-copy=false.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateBase64Recordings() {
        List<Long> ids;
        try {
            ids = jdbcTemplate.queryForList("SELECT id FROM virtual_classes WHERE recording_data IS NOT NULL "
                + "AND recording_data <> ''", Long.class);
        } catch (Exception e) {
            log.warn("Migration des enregistrements impossible : {}", e.getMessage());
            return;
        }
        for (Long id : ids) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    VirtualClass vc = virtualClassRepository.findById(id).orElse(null);
                    if (vc == null || vc.getRecordingData() == null) return;
                    if (fileAvailable(vc)) {
                        if (!keepDbCopy) { vc.setRecordingData(null); virtualClassRepository.save(vc); }
                        return;
                    }
                    try {
                        byte[] bytes = Base64.getDecoder().decode(vc.getRecordingData());
                        String ext = vc.getRecordingMimeType() != null && vc.getRecordingMimeType().contains("mp4") ? ".mp4" : ".webm";
                        String url = fileStorageService.storeBytes(bytes, DIR, ext);
                        vc.setRecordingUrl(url);
                        if (!keepDbCopy) vc.setRecordingData(null);
                        virtualClassRepository.save(vc);
                        videoOptimizer.optimizeLater(url);
                        log.info("Enregistrement de la séance #{} déplacé en fichier ({} Ko)", id, bytes.length / 1024);
                    } catch (IOException | IllegalArgumentException e) {
                        throw new IllegalStateException(e);
                    }
                });
            } catch (Exception e) {
                log.warn("Enregistrement de la séance #{} non migré : {}", id, e.getMessage());
            }
        }
    }
}
