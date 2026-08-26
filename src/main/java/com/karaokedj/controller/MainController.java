package com.karaokedj.controller;

import com.karaokedj.lyrics.AiLyricsPipeline;
import com.karaokedj.lyrics.LyricsSearchService;
import com.karaokedj.ml.WhisperLanguage;
import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;
import com.karaokedj.service.AudioPlayerService;
import com.karaokedj.service.AudioSeparationModel;
import com.karaokedj.service.LrcFileService;
import com.karaokedj.service.MetadataService;
import com.karaokedj.service.ProgressListener;
import com.karaokedj.service.SongRecognitionService;
import com.karaokedj.util.LrcTime;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MainController {

    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @FXML private ListView<SongMetadata> songList;
    @FXML private Label lblTitle;
    @FXML private Label lblArtist;
    @FXML private Label lblAlbum;
    @FXML private Label lblDuration;
    @FXML private Label lblLrcStatus;
    @FXML private TextArea txtLyrics;
    @FXML private Button btnSearchLyrics;
    @FXML private Button btnProcessAi;
    @FXML private Button btnProcessVocal;
    @FXML private Button btnSaveLrc;
    @FXML private Button btnPlay;
    @FXML private Button btnPause;
    @FXML private Button btnStop;
    @FXML private Slider sliderProgress;
    @FXML private Slider sliderVolume;
    @FXML private Label lblCurrentTime;
    @FXML private Label lblTotalTime;
    @FXML private Label lblStatus;
    @FXML private Label lblHardware;
    @FXML private ProgressBar progressBar;
    @FXML private ComboBox<String> cmbSeparationModel;
    @FXML private ComboBox<String> cmbWhisperLanguage;

    @Autowired
    private MetadataService metadataService;

    @Autowired
    private SongRecognitionService songRecognitionService;

    @Autowired
    private LyricsSearchService lyricsSearchService;

    @Autowired
    private AiLyricsPipeline aiLyricsPipeline;

    @Autowired
    private LrcFileService lrcFileService;

    @Autowired
    private AudioPlayerService audioPlayerService;

    private final ObservableList<SongMetadata> songs = FXCollections.observableArrayList();
    private SongMetadata selectedSong;
    private final Set<Path> recognitionAttempted = ConcurrentHashMap.newKeySet();
    private LrcLyrics currentLyrics;

    // ==== Estado del log de progreso ====
    private final List<String> progressLog = new java.util.ArrayList<>();
    private long taskStartMs;
    private boolean lastLineWasDetail;

    /** Preferencia persistente: modelo de separación elegido. */
    private static final String PREF_SEPARATION_MODEL = "separation.model";
    /** Preferencia persistente: idioma de transcripción (código, robusto a reordenamientos). */
    private static final String PREF_WHISPER_LANGUAGE = "whisper.language";
    private final java.util.prefs.Preferences prefs =
            java.util.prefs.Preferences.userNodeForPackage(MainController.class);

    @FXML
    public void initialize() {
        songList.setCellFactory(list -> new SongCellFactory());
        songList.setItems(songs);
        songList.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldVal, newVal) -> onSongSelected(newVal));

        sliderVolume.valueProperty().addListener((obs, oldVal, newVal) -> {
            audioPlayerService.setVolume(newVal.doubleValue() / 100.0);
        });
        sliderVolume.setValue(80);

        setupSeparationModelSelector();
        setupWhisperLanguageSelector();

        // Actualizar UI con el hardware detectado
        String hwInfo = com.karaokedj.ml.MlOptions.getHardwareInfo();
        lblHardware.setText(hwInfo);
        if (hwInfo.startsWith("GPU")) {
            lblHardware.setStyle("-fx-text-fill: #a6e3a1; -fx-font-size: 11; -fx-font-weight: bold;");
        } else {
            lblHardware.setStyle("-fx-text-fill: #f38ba8; -fx-font-size: 11; -fx-font-weight: bold;");
        }

        // La posición de reproducción llega como evento; sin hilos de polling.
        audioPlayerService.currentTimeProperty().addListener((obs, oldVal, val) -> updatePlaybackUi());
    }

    private void setupSeparationModelSelector() {
        for (AudioSeparationModel engine : AudioSeparationModel.values()) {
            cmbSeparationModel.getItems().add(engine.uiLabel());
        }
        int savedIndex = prefs.getInt(PREF_SEPARATION_MODEL, AudioSeparationModel.DEMUCS.ordinal());
        cmbSeparationModel.getSelectionModel().select(
                Math.max(0, Math.min(savedIndex, AudioSeparationModel.values().length - 1)));
        cmbSeparationModel.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                prefs.putInt(PREF_SEPARATION_MODEL, cmbSeparationModel.getSelectionModel().getSelectedIndex());
            }
        });
    }

    private void setupWhisperLanguageSelector() {
        for (WhisperLanguage lang : WhisperLanguage.values()) {
            cmbWhisperLanguage.getItems().add(lang.uiLabel());
        }
        String savedCode = prefs.get(PREF_WHISPER_LANGUAGE, WhisperLanguage.ES.code());
        cmbWhisperLanguage.getSelectionModel().select(WhisperLanguage.fromCode(savedCode).ordinal());
        cmbWhisperLanguage.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                int idx = cmbWhisperLanguage.getSelectionModel().getSelectedIndex();
                prefs.put(PREF_WHISPER_LANGUAGE, WhisperLanguage.values()[idx].code());
            }
        });
    }

    private AudioSeparationModel selectedSeparationModel() {
        return AudioSeparationModel.fromUiIndex(cmbSeparationModel.getSelectionModel().getSelectedIndex());
    }

    private WhisperLanguage selectedWhisperLanguage() {
        int idx = cmbWhisperLanguage.getSelectionModel().getSelectedIndex();
        return idx >= 0 ? WhisperLanguage.values()[idx] : WhisperLanguage.ES;
    }

    // ============================================================
    // Acciones de biblioteca
    // ============================================================

    @FXML
    public void onOpenFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Seleccionar Archivo de Audio");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio", "*.mp3", "*.flac", "*.ogg", "*.wav", "*.opus", "*.m4a"),
                new FileChooser.ExtensionFilter("Todos", "*.*")
        );

        File selectedFile = fileChooser.showOpenDialog(songList.getScene().getWindow());
        if (selectedFile != null) {
            loadAudioFile(selectedFile.toPath());
        }
    }

    @FXML
    public void onOpenFolder() {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Seleccionar Carpeta de Musica");

        File selectedDir = dirChooser.showDialog(songList.getScene().getWindow());
        if (selectedDir != null) {
            loadFolder(selectedDir.toPath());
        }
    }

    /** Agrega la canción si no está ya en la lista; true si se agregó. */
    private boolean addSongIfNew(Path filePath) throws Exception {
        boolean exists = songs.stream().anyMatch(s -> s.getFilePath().equals(filePath));
        if (exists) return false;
        songs.add(metadataService.extractMetadata(filePath));
        return true;
    }

    private void loadAudioFile(Path filePath) {
        try {
            addSongIfNew(filePath);
            songs.stream()
                    .filter(s -> s.getFilePath().equals(filePath))
                    .findFirst()
                    .ifPresent(songList.getSelectionModel()::select);
        } catch (Exception e) {
            showAlert("Error", "No se pudo leer: " + filePath.getFileName() + "\n" + e.getMessage());
        }
    }

    private void loadFolder(Path folderPath) {
        try {
            List<Path> audioFiles;
            try (var stream = Files.list(folderPath)) {
                audioFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(metadataService::isSupportedFormat)
                        .sorted()
                        .toList();
            }

            if (audioFiles.isEmpty()) {
                showAlert("Info", "No se encontraron archivos de audio en:\n" + folderPath);
                return;
            }

            int loaded = 0;
            for (Path file : audioFiles) {
                try {
                    if (addSongIfNew(file)) loaded++;
                } catch (Exception e) {
                    log.warn("Skipping file {}: {}", file.getFileName(), e.getMessage());
                }
            }

            lblStatus.setText(loaded + " canciones cargadas de " + audioFiles.size() + " archivos");
            if (!songs.isEmpty()) {
                songList.getSelectionModel().select(0);
            }
        } catch (Exception e) {
            showAlert("Error", "Error al leer carpeta: " + e.getMessage());
        }
    }

    private void onSongSelected(SongMetadata song) {
        if (song == null) return;

        selectedSong = song;
        currentLyrics = null;
        txtLyrics.setText("");
        btnSaveLrc.setDisable(true);

        lblTitle.setText(song.getTitle());
        lblArtist.setText(song.getArtist());
        lblAlbum.setText(song.getAlbum());
        lblDuration.setText(song.getDurationFormatted());
        lblLrcStatus.setText("Letra no buscada");

        audioPlayerService.stop();
        updatePlayButtons(false);

        // Intentar reconocimiento automático si la canción tiene metadatos débiles
        maybeAutoRecognize(song);
    }

    /**
     * Verifica si la canción seleccionada tiene metadatos débiles (artista vacío o título = nombre de archivo).
     * Si sí, y aún no se intentó para este archivo y no hay tarea larga activa, lanza el reconocimiento.
     */
    private void maybeAutoRecognize(SongMetadata song) {
        if (song == null) return;
        if (!metadataService.hasWeakMetadata(song)) return;
        if (!recognitionAttempted.add(song.getFilePath())) return; // ya intentado esta sesión
        if (btnSearchLyrics.isDisable()) return; // tarea larga activa; esperar

        // Verificar que haya cliente AcoustID configurado
        if (songRecognitionService.getClientId() == null || songRecognitionService.getClientId().isBlank()) {
            appendLog("Reconocimiento: clave AcoustID no configurada. Regístrate en acoustid.org y configura la clave.");
            lblStatus.setText("Sin reconocimiento (clave faltante)");
            return;
        }

        lblStatus.setText("Reconociendo canción...");
        showProgressBarIndeterminate();

        Task<Optional<SongRecognitionService.RecognitionResult>> task = new Task<>() {
            @Override
            protected Optional<SongRecognitionService.RecognitionResult> call() throws Exception {
                return songRecognitionService.recognize(song.getFilePath());
            }
        };

        task.setOnSucceeded(event -> {
            finishProgressBar();
            Optional<SongRecognitionService.RecognitionResult> optResult = task.getValue();
            if (optResult.isPresent()) {
                SongRecognitionService.RecognitionResult rr = optResult.get();
                // Actualizar metadatos en memoria
                selectedSong.setTitle(rr.title());
                selectedSong.setArtist(rr.artist());
                selectedSong.setAlbum(rr.album());
                // Escribir tags al archivo
                try {
                    metadataService.writeTags(song.getFilePath(), rr.title(), rr.artist(), rr.album());
                    appendLog("Tags escritas: " + rr.title() + " - " + rr.artist());
                } catch (Exception e) {
                    appendLog("No se pudieron escribir tags: " + e.getMessage());
                }
                // Actualizar labels de UI
                lblTitle.setText(rr.title());
                lblArtist.setText(rr.artist());
                lblAlbum.setText(rr.album() != null ? rr.album() : "Sin álbum");
                lblStatus.setText("Canción reconocida: " + rr.title() + " - " + rr.artist());
                appendLog("Reconocida: " + rr.title() + " - " + rr.artist() + " (score: " + rr.score() + ")");
                // Encadenar búsqueda de letra automáticamente
                onSearchLyrics();
            } else {
                appendLog("No se pudo reconocer la canción; intenta con otro archivo o verifica la clave AcoustID.");
                lblStatus.setText("Sin reconocimiento");
            }
        });

        task.setOnFailed(event -> {
            finishProgressBar();
            Throwable ex = task.getException();
            String msg = ex != null ? ex.getMessage() : "Error desconocido";
            appendLog("Error en reconocimiento: " + msg);
            lblStatus.setText("Error en reconocimiento");
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    // ============================================================
    // Tareas largas (búsqueda / IA / vocal)
    // ============================================================

    /**
     * Ejecuta una tarea larga encapsulando el ciclo completo de UI:
     * deshabilitar botones → log inicial → barra indeterminada → hilo daemon →
     * handlers de éxito/error → rehabilitar botones. Reemplaza 3 bloques idénticos.
     */
    private void runTask(TaskSpec spec, java.util.concurrent.Callable<LrcLyrics> work,
                         ProgressListener listener,
                         java.util.function.Consumer<LrcLyrics> onSuccessFx) {
        spec.busyButtons().forEach(b -> b.setDisable(true));
        lblStatus.setText(spec.busyStatusLabel());
        lblLrcStatus.setText(spec.lrcBusyStatus());
        beginProgressLog(spec.initialLogLine());
        showProgressBarIndeterminate();

        Task<LrcLyrics> task = new Task<>() {
            @Override
            protected LrcLyrics call() throws Exception {
                return work.call();
            }
        };

        task.setOnSucceeded(event -> {
            finishProgressBar();
            try {
                onSuccessFx.accept(task.getValue());
            } finally {
                reEnableButtons(spec);
            }
        });

        task.setOnFailed(event -> {
            finishProgressBar();
            Throwable ex = task.getException();
            String errMsg = ex.getMessage();
            if (errMsg == null || errMsg.isBlank()) errMsg = ex.toString();
            appendLog(spec.errorLogPrefix() + ": " + errMsg);
            lblLrcStatus.setText(spec.errorStatusLabel());
            lblStatus.setText(spec.errorStatusLabel());
            reEnableButtons(spec);
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private void reEnableButtons(TaskSpec spec) {
        spec.busyButtons().forEach(b -> b.setDisable(false));
    }

    /** Parámetros de presentación de una tarea larga (evita listas largas de argumentos). */
    private record TaskSpec(List<Button> busyButtons, String busyStatusLabel, String lrcBusyStatus,
                            String initialLogLine, String errorLogPrefix, String errorStatusLabel) {}

    @FXML
    public void onSearchLyrics() {
        if (selectedSong == null) return;

        SongMetadata song = selectedSong;
        AudioSeparationModel engine = selectedSeparationModel();
        WhisperLanguage language = selectedWhisperLanguage();
        runTask(
                new TaskSpec(List.of(btnSearchLyrics),
                        "Buscando letra...", "Buscando...",
                        String.format("Buscando letra para \"%s\" - \"%s\"...", song.getTitle(), song.getArtist()),
                        "Error al buscar letra", "Error"),
                () -> lyricsSearchService.search(song, uiListener(), engine, language),
                uiListener(),
                lyrics -> {
                    currentLyrics = lyrics;
                    if (lyrics != null && (lyrics.hasEnhancedLyrics() || lyrics.hasSyncedLyrics() || lyrics.hasPlainLyrics())) {
                        displayLyrics(lyrics);
                        btnSaveLrc.setDisable(false);
                        lblStatus.setText("Letra encontrada");
                        lblLrcStatus.setText(describeSource(lyrics));
                    } else {
                        appendLog("No se encontro letra para esta cancion.");
                        lblLrcStatus.setText("No encontrada");
                        lblStatus.setText("Sin resultados");
                    }
                });
    }

    @FXML
    public void onProcessAi() {
        if (selectedSong == null) return;

        SongMetadata song = selectedSong;
        AudioSeparationModel engine = selectedSeparationModel();
        WhisperLanguage language = selectedWhisperLanguage();
        runTask(
                new TaskSpec(List.of(btnSearchLyrics, btnProcessAi),
                        "Procesando con IA...", "Procesando IA...",
                        "Iniciando pipeline de IA (" + engine.displayName() + " + Whisper)...",
                        "Error en pipeline IA", "Error IA"),
                () -> aiLyricsPipeline.process(song, null, uiListener(), engine, language),
                uiListener(),
                lyrics -> {
                    currentLyrics = lyrics;
                    if (lyrics != null && lyrics.hasEnhancedLyrics()) {
                        displayLyrics(lyrics);
                        btnSaveLrc.setDisable(false);
                        lblStatus.setText("IA completada");
                        lblLrcStatus.setText("Karaoke IA (" + lyrics.getSource() + ")");
                    } else {
                        appendLog("El pipeline de IA no genero resultado.");
                        lblLrcStatus.setText("Error IA");
                        lblStatus.setText("Error");
                    }
                });
    }

    @FXML
    public void onProcessVocal() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Seleccionar Vocal Aislada (WAV)");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("WAV", "*.wav"),
                new FileChooser.ExtensionFilter("Todos", "*.*")
        );

        File selectedFile = fileChooser.showOpenDialog(songList.getScene().getWindow());
        if (selectedFile == null) return;

        if (selectedSong == null) {
            showAlert("Error", "Seleccione una cancion de la lista primero.");
            return;
        }

        Path vocalWav = selectedFile.toPath();
        SongMetadata song = selectedSong;
        WhisperLanguage language = selectedWhisperLanguage();
        runTask(
                new TaskSpec(List.of(btnSearchLyrics, btnProcessAi, btnProcessVocal),
                        "Procesando vocal...", "Procesando vocal...",
                        "Transcribiendo vocal aislada: " + selectedFile.getName(),
                        "Error al procesar vocal", "Error vocal"),
                () -> aiLyricsPipeline.processVocalOnly(vocalWav, song, uiListener(), language),
                uiListener(),
                lyrics -> {
                    currentLyrics = lyrics;
                    if (lyrics != null && lyrics.hasEnhancedLyrics()) {
                        displayLyrics(lyrics);
                        btnSaveLrc.setDisable(false);
                        lblStatus.setText("Vocal transcrito");
                        lblLrcStatus.setText("Karaoke Whisper (vocal directo)");
                    } else {
                        appendLog("Whisper no genero resultado para este vocal.");
                        lblLrcStatus.setText("Error vocal");
                        lblStatus.setText("Error");
                    }
                });
    }

    /** Árbol de decisión del estado de letra tras la búsqueda en repositorios. */
    private String describeSource(LrcLyrics lyrics) {
        boolean exists = lrcFileService.lrcFileExists(selectedSong.getFilePath());
        String existSuffix = exists ? " (ya existe .lrc)" : "";

        String source = lyrics.getSource();
        if (source != null && source.contains("IA")) {
            return "Karaoke IA (" + source + ")" + existSuffix;
        } else if (lyrics.hasEnhancedLyrics() && lyrics.isWordSynced() && lyrics.isSyncedFromRepo()) {
            return "Karaoke (" + source + ")" + existSuffix;
        } else if (lyrics.hasEnhancedLyrics() && lyrics.isSyncedFromRepo()) {
            return "Sincronizada (" + source + ")" + existSuffix;
        } else if (lyrics.hasEnhancedLyrics()) {
            return "Karaoke (generada)" + existSuffix;
        } else if (lyrics.hasSyncedLyrics()) {
            return "Sincronizada (" + source + ")" + existSuffix;
        }
        return "Solo texto plano - " + source + existSuffix;
    }

    @FXML
    public void onSaveLrc() {
        if (currentLyrics == null || selectedSong == null) return;
        try {
            Path lrcPath = lrcFileService.saveLrcFile(selectedSong, currentLyrics);
            lblStatus.setText("Guardado: " + lrcPath.getFileName());
        } catch (Exception e) {
            lblStatus.setText("Error al guardar");
            showAlert("Error", "No se pudo guardar: " + e.getMessage());
        }
    }

    // ============================================================
    // Log de progreso y barra
    // ============================================================

    private void beginProgressLog(String initialLine) {
        progressLog.clear();
        lastLineWasDetail = false;
        taskStartMs = System.currentTimeMillis();
        appendLog(initialLine);
    }

    private void appendLog(String line) {
        long elapsed = (System.currentTimeMillis() - taskStartMs) / 1000;
        String stamped = String.format("[%02d:%02d] %s", elapsed / 60, elapsed % 60, line);
        progressLog.add(stamped);
        lastLineWasDetail = false;
        txtLyrics.setText(String.join("\n", progressLog));
        txtLyrics.setScrollTop(Double.MAX_VALUE);
    }

    private void replaceOrAppendDetail(String detail) {
        long elapsed = (System.currentTimeMillis() - taskStartMs) / 1000;
        String stamped = String.format("[%02d:%02d]   %s", elapsed / 60, elapsed % 60, detail);
        if (lastLineWasDetail && !progressLog.isEmpty()) {
            progressLog.set(progressLog.size() - 1, stamped);
        } else {
            progressLog.add(stamped);
            lastLineWasDetail = true;
        }
        txtLyrics.setText(String.join("\n", progressLog));
        txtLyrics.setScrollTop(Double.MAX_VALUE);
    }

    private void showProgressBarIndeterminate() {
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(-1.0);
    }

    private void finishProgressBar() {
        progressBar.setVisible(false);
        progressBar.setManaged(false);
        progressBar.setProgress(0);
    }

    /** Listener que recibe eventos de los servicios (hilos background) y actualiza la UI. */
    private ProgressListener uiListener() {
        return new ProgressListener() {
            @Override
            public void onStep(String step) {
                Platform.runLater(() -> {
                    appendLog(step);
                    lblStatus.setText(step);
                });
            }

            @Override
            public void onProgress(String step, long bytes) {
                Platform.runLater(() -> {
                    replaceOrAppendDetail(step + " (" + formatBytes(bytes) + ")");
                    lblStatus.setText(step + " (" + formatBytes(bytes) + ")");
                });
            }

            @Override
            public void onDetail(String detail) {
                Platform.runLater(() -> replaceOrAppendDetail(detail));
            }

            @Override
            public void onFraction(double fraction) {
                Platform.runLater(() -> {
                    progressBar.setVisible(true);
                    progressBar.setManaged(true);
                    progressBar.setProgress(fraction);
                });
            }
        };
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // ============================================================
    // Reproducción
    // ============================================================

    @FXML
    public void onPlay() {
        if (selectedSong != null && !audioPlayerService.isPlaying()) {
            if (audioPlayerService.getCurrentFile() == null ||
                    !audioPlayerService.getCurrentFile().equals(selectedSong.getFilePath())) {
                audioPlayerService.load(selectedSong.getFilePath());
            }
            audioPlayerService.play();
            updatePlayButtons(true);
            lblStatus.setText("Reproduciendo");
        }
    }

    @FXML
    public void onPause() {
        audioPlayerService.pause();
        updatePlayButtons(false);
        lblStatus.setText("Pausado");
    }

    @FXML
    public void onStop() {
        audioPlayerService.stop();
        updatePlayButtons(false);
        sliderProgress.setValue(0);
        lblCurrentTime.setText("00:00");
        lblStatus.setText("Detenido");
    }

    private void updatePlayButtons(boolean playing) {
        btnPlay.setDisable(playing);
        btnPause.setDisable(!playing);
    }

    private void updatePlaybackUi() {
        double total = audioPlayerService.getTotalDuration();
        if (total <= 0) return;

        double current = audioPlayerService.getCurrentTime();
        sliderProgress.setValue((current / total) * 100);
        lblCurrentTime.setText(LrcTime.clock(current));
        lblTotalTime.setText(LrcTime.clock(total));
    }

    private void displayLyrics(LrcLyrics lyrics) {
        if (lyrics.hasEnhancedLyrics()) {
            txtLyrics.setText(lyrics.getEnhancedLyrics());
        } else if (lyrics.hasSyncedLyrics()) {
            txtLyrics.setText(lyrics.getSyncedLyrics());
        } else if (lyrics.hasPlainLyrics()) {
            String header = "[Letra sin sincronizar - no se encontro letra sincronizada en ningun repositorio]\n\n";
            txtLyrics.setText(header + lyrics.getPlainLyrics());
        } else {
            txtLyrics.setText("No hay letra disponible");
        }
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
