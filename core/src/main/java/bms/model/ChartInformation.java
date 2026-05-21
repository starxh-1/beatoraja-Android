package bms.model;

import com.badlogic.gdx.files.FileHandle;

public class ChartInformation {

	public final String path;
	public final FileHandle fileHandle;

	public final int lntype;

	public final int[] selectedRandoms;

	public ChartInformation(String path, int lntype, int[] selectedRandoms) {
		this.path = path;
		this.fileHandle = null;
		this.lntype = lntype;
		this.selectedRandoms = selectedRandoms;
	}

	public ChartInformation(FileHandle fileHandle, int lntype, int[] selectedRandoms) {
		this.path = null;
		this.fileHandle = fileHandle;
		this.lntype = lntype;
		this.selectedRandoms = selectedRandoms;
	}

}
