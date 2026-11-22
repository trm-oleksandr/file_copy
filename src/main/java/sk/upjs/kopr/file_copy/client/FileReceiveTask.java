package sk.upjs.kopr.file_copy.client;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.function.LongConsumer;

import sk.upjs.kopr.file_copy.FileRequest;

public class FileReceiveTask implements Callable<Void>{
	private static final int BUFFER_SIZE = 16384;
	private MyFileWriter myFileWriter;
	private CopyState copyState;
	private int part; // index of the file part received by this task
	private InetAddress inetAddress;
	private int serverPort;
	private CountDownLatch latch;
	private LongConsumer progressListener; // receives total number of received bytes
	
	public FileReceiveTask(File fileToSave, CopyState copyState, int part, InetAddress inetAddress, int serverPort,
						   CountDownLatch latch, LongConsumer progressListener) throws IOException {
		this.copyState = copyState;
		this.part = part;
		this.inetAddress = inetAddress;
		this.serverPort = serverPort;
		this.latch = latch;
		this.progressListener = progressListener;
		myFileWriter = MyFileWriter.getInstance(fileToSave, copyState.getFileSize());
	}

	@Override
	public Void call() throws Exception {
		try(Socket socket = new Socket(inetAddress, serverPort)) {
			ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());
			ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
			long fileOffset = copyState.getPosition(part);
			long end = copyState.getPartEnd(part);
			oos.writeUTF("file");
			oos.flush();
			FileRequest fileRequest = new FileRequest(fileOffset, end - fileOffset);
			oos.writeObject(fileRequest);
			oos.flush();
			while(fileOffset < end) {
				if (Thread.currentThread().isInterrupted()) {
					throw new InterruptedException("Receiving of part " + part + " interrupted");
				}
				int size = (int) Math.min(BUFFER_SIZE, end - fileOffset);
				byte[] bytes;
				try {
					bytes = ois.readNBytes(size);
				} catch (IOException e) {
					throw new IOException("Connection to server lost", e);
				}
				myFileWriter.write(fileOffset, bytes, 0, bytes.length);
				fileOffset += bytes.length;
				copyState.setPosition(part, fileOffset);
				progressListener.accept(copyState.getReceived());
				if (bytes.length < size) {
					throw new EOFException("Connection to server lost");
				}
			}
		} finally {
			latch.countDown();
		}
		return null;
	}

}
