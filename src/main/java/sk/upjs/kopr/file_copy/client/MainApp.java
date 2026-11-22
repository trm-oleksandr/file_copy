package sk.upjs.kopr.file_copy.client;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Application;
import javafx.concurrent.WorkerStateEvent;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class MainApp extends Application {

	public static final String SERVER_HOST = "localhost";
	public static final File DESTINATION_DIR = new File("./");
	public static final File STATE_FILE = new File(DESTINATION_DIR, "file_copy.state");

	private ExecutorService executor = Executors.newSingleThreadExecutor();
	private CopyService copyService;

	@Override
	public void start(Stage primaryStage) throws Exception {
		Spinner<Integer> socketsSpinner = new Spinner<>(1, 32, 4);
		Button copyButton = new Button("Copy");
		ProgressBar progressBar = new ProgressBar(0);
		progressBar.setPrefWidth(400);
		Label progressLabel = new Label();
		Label statusLabel = new Label();

		HBox controlsPane = new HBox(10, new Label("Number of sockets:"), socketsSpinner, copyButton);
		VBox rootPane = new VBox(10, controlsPane, progressBar, progressLabel, statusLabel);
		rootPane.setPadding(new Insets(10));
		Scene scene = new Scene(rootPane);

		primaryStage.setScene(scene);
		primaryStage.setTitle("KOPR file copy");
		primaryStage.show();

		if (STATE_FILE.exists()) {
			// previous copying was interrupted
			CopyState copyState = CopyState.load(STATE_FILE);
			socketsSpinner.getValueFactory().setValue(copyState.getNumberOfParts());
			progressBar.setProgress((double) copyState.getReceived() / copyState.getFileSize());
			progressLabel.setText(copyState.getReceived() / 1_000_000 + " MB / " + copyState.getFileSize() / 1_000_000 + " MB ("
								  + copyState.getReceived() * 100 / copyState.getFileSize() + " %)");
			statusLabel.setText("Copying of " + copyState.getFileName() + " was interrupted");
			copyState.close();
			socketsSpinner.setDisable(true);
			copyButton.setText("Continue");
		}

		copyService = new CopyService(socketsSpinner.valueProperty());
		copyService.setExecutor(executor);
		copyButton.disableProperty().bind(copyService.runningProperty());

		copyButton.setOnAction(new EventHandler<ActionEvent>() {
			@Override
			public void handle(ActionEvent event) {
				socketsSpinner.setDisable(true);
				progressBar.progressProperty().bind(copyService.progressProperty());
				progressLabel.textProperty().bind(copyService.messageProperty());
				statusLabel.setText("Copying...");
				copyService.restart();
			}
		});

		copyService.setOnSucceeded(new EventHandler<WorkerStateEvent>() {
			@Override
			public void handle(WorkerStateEvent event) {
				statusLabel.setText("Copying finished");
				copyButton.setText("Copy");
				socketsSpinner.setDisable(false);
			}
		});

		copyService.setOnFailed(new EventHandler<WorkerStateEvent>() {
			@Override
			public void handle(WorkerStateEvent event) {
				statusLabel.setText(copyService.getException().getMessage());
				if (STATE_FILE.exists()) {
					copyButton.setText("Continue");
				} else {
					socketsSpinner.setDisable(false);
				}
			}
		});
	}

	@Override
	public void stop() throws Exception {
		// cancels copying, executor thread closes sockets and files before the end of the program
		copyService.cancel();
		executor.shutdown();
	}

	public static void main(String[] args) {
		launch(args);
	}

}
