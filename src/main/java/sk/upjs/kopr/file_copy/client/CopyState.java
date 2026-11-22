package sk.upjs.kopr.file_copy.client;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

import sk.upjs.kopr.file_copy.FileInfo;

public class CopyState {
	private RandomAccessFile raf;
	private String fileName;
	private long fileSize;
	private long[] positions; // position of next byte to receive in each part
	private long positionsOffset; // position of the first part's position in the state file

	private CopyState(RandomAccessFile raf, String fileName, long fileSize, long[] positions, long positionsOffset) {
		this.raf = raf;
		this.fileName = fileName;
		this.fileSize = fileSize;
		this.positions = positions;
		this.positionsOffset = positionsOffset;
	}

	public static CopyState create(File stateFile, FileInfo fileInfo, int numberOfParts) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(stateFile, "rw");
		raf.setLength(0);
		raf.writeUTF(fileInfo.fileName);
		raf.writeLong(fileInfo.size);
		raf.writeInt(numberOfParts);
		long positionsOffset = raf.getFilePointer();
		long[] positions = new long[numberOfParts];
		for (int i = 0; i < numberOfParts; i++) {
			positions[i] = partStart(fileInfo.size, numberOfParts, i);
			raf.writeLong(positions[i]);
		}
		return new CopyState(raf, fileInfo.fileName, fileInfo.size, positions, positionsOffset);
	}

	public static CopyState load(File stateFile) throws IOException {
		RandomAccessFile raf = new RandomAccessFile(stateFile, "rw");
		String fileName = raf.readUTF();
		long fileSize = raf.readLong();
		int numberOfParts = raf.readInt();
		long positionsOffset = raf.getFilePointer();
		long[] positions = new long[numberOfParts];
		for (int i = 0; i < numberOfParts; i++) {
			positions[i] = raf.readLong();
		}
		return new CopyState(raf, fileName, fileSize, positions, positionsOffset);
	}

	private static long partStart(long fileSize, int numberOfParts, int part) {
		return part * (fileSize / numberOfParts);
	}

	public synchronized void setPosition(int part, long position) throws IOException {
		positions[part] = position;
		raf.seek(positionsOffset + part * 8L);
		raf.writeLong(position);
	}

	public synchronized long getPosition(int part) {
		return positions[part];
	}

	public long getPartEnd(int part) {
		if (part == positions.length - 1) {
			return fileSize;
		}
		return partStart(fileSize, positions.length, part + 1);
	}

	public synchronized long getReceived() {
		long received = 0;
		for (int i = 0; i < positions.length; i++) {
			received += positions[i] - partStart(fileSize, positions.length, i);
		}
		return received;
	}

	public int getNumberOfParts() {
		return positions.length;
	}

	public String getFileName() {
		return fileName;
	}

	public long getFileSize() {
		return fileSize;
	}

	public void close() {
		try {
			raf.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
