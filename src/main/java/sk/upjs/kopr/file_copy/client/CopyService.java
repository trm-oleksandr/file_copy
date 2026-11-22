package sk.upjs.kopr.file_copy.client;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import sk.upjs.kopr.file_copy.FileInfo;
import sk.upjs.kopr.file_copy.server.Server;

public class CopyService extends Service<Void> {
	private final ReadOnlyObjectProperty<Integer> numberOfSocketsProperty;

	public CopyService(ReadOnlyObjectProperty<Integer> numberOfSocketsProperty) {
		this.numberOfSocketsProperty = numberOfSocketsProperty;
	}

	@Override
	protected Task<Void> createTask() {
		final int numberOfSockets = numberOfSocketsProperty.get(); // createTask() runs in JAT
		return new Task<Void>() {
			@Override
			protected Void call() throws Exception {
				FileInfo fileInfo = new FileInfoReceiver(MainApp.SERVER_HOST, Server.SERVER_PORT).getFileInfo();
				if (fileInfo == null) {
					throw new IOException("Server connection problem. Is server running?");
				}
				CopyState copyState;
				if (MainApp.STATE_FILE.exists()) {
					copyState = CopyState.load(MainApp.STATE_FILE);
					if (! copyState.getFileName().equals(fileInfo.fileName) || copyState.getFileSize() != fileInfo.size) {
						copyState.close();
						throw new IOException("Server shares another file than the interrupted one");
					}
				} else {
					copyState = CopyState.create(MainApp.STATE_FILE, fileInfo, numberOfSockets);
				}
				File file = new File(MainApp.DESTINATION_DIR, fileInfo.fileName);
				MyFileWriter myFileWriter = MyFileWriter.getInstance(file, fileInfo.size);
				InetAddress inetAddress = InetAddress.getByName(MainApp.SERVER_HOST);
				LongConsumer progressListener = new LongConsumer() {
					@Override
					public void accept(long received) {
						updateProgress(received, fileInfo.size);
						updateMessage(received / 1_000_000 + " MB / " + fileInfo.size / 1_000_000 + " MB ("
									  + received * 100 / fileInfo.size + " %)");
					}
				};
				progressListener.accept(copyState.getReceived());

				int numberOfParts = copyState.getNumberOfParts();
				ExecutorService executor = Executors.newFixedThreadPool(numberOfParts);
				CountDownLatch latch = new CountDownLatch(numberOfParts);
				List<Future<Void>> futures = new ArrayList<>();
				try {
					for (int i = 0; i < numberOfParts; i++) {
						FileReceiveTask task = new FileReceiveTask(file, copyState, i, inetAddress, Server.SERVER_PORT,
																   latch, progressListener);
						futures.add(executor.submit(task));
					}
					try {
						latch.await();
					} catch (InterruptedException e) {
						// copying cancelled by user, interrupting all FileReceiveTasks
						executor.shutdownNow();
						executor.awaitTermination(1, TimeUnit.DAYS);
						System.out.println("Copying interrupted after " + copyState.getReceived() / 1_000_000 + " MB");
						throw e;
					}
					for (Future<Void> future : futures) {
						try {
							future.get();
						} catch (ExecutionException e) {
							throw (Exception) e.getCause();
						}
					}
				} finally {
					executor.shutdown();
					myFileWriter.close();
					copyState.close();
				}
				MainApp.STATE_FILE.delete();
				return null;
			}
		};
	}
}
