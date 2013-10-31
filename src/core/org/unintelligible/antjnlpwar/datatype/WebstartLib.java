/*
 * Created on 18-Apr-2005
 */
package org.unintelligible.antjnlpwar.datatype;

import org.apache.tools.ant.taskdefs.Manifest;
import org.apache.tools.ant.types.ZipFileSet;


public class WebstartLib extends ZipFileSet {
	
	private Manifest
		manifest = new Manifest();
	
	public void setManifest(Manifest manifest) {
		this.manifest = manifest;
	}
	public Manifest getManifest() {
		return manifest;
	}
	
}
