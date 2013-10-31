/*
 * This file has been split up in two for readability - this
 * one performs the actual task
 * Created on 18-Apr-2005
 */
package org.unintelligible.antjnlpwar.task;

import java.io.File;
import java.io.FileFilter;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.FileScanner;
import org.apache.tools.ant.taskdefs.Delete;
import org.apache.tools.ant.taskdefs.Jar;
import org.apache.tools.ant.taskdefs.Manifest;
import org.apache.tools.ant.taskdefs.ManifestException;
import org.apache.tools.ant.taskdefs.SignJar;
import org.apache.tools.ant.taskdefs.Zip;
import org.unintelligible.antjnlpwar.datatype.Icon;
import org.unintelligible.antjnlpwar.datatype.J2se;
import org.unintelligible.antjnlpwar.datatype.NativeLib;
import org.unintelligible.antjnlpwar.datatype.WebstartLib;
import org.unintelligible.antjnlpwar.generation.Generator;
import org.unintelligible.antjnlpwar.util.StreamUtil;

import com.sun.tools.apache.ant.pack200.Pack200Task;
import com.sun.tools.apache.ant.pack200.Unpack200Task;

/**
 * 
 * @author ngc
 * 
 */
public class JnlpWar extends BaseJnlpWar {

	private FileFilter jarFileFilter=new FileFilter() {
		public boolean accept(File pathname) {
			if(pathname.isFile() && pathname.getName().endsWith(".jar")){
				return true;
			}
			return false;
		}
	};
	private FileFilter pack200FileFilter=new FileFilter() {
		public boolean accept(File pathname) {
			if(pathname.isFile() && pathname.getName().endsWith(".jar.pack.gz")){
				return true;
			}
			return false;
		}
	};
	/*
	 * Main methods
	 *  
	 */

	/**
	 * Execute the task
	 * 
	 * @see org.apache.tools.ant.Task#execute()
	 */
	public void execute() {
		verifyInput();
		log("Vendor=" + getVendor());
		//create a folder in the temp dir
		File tempFolder = new File(System.getProperty("java.io.tmpdir"));
		if (!tempFolder.canWrite()) {
			throw new BuildException(
					"Cannot write to the current user's temp folder in "
							+ tempFolder.getAbsolutePath());
		}
		//create the folder hierarchy
		File rootFolder = createFolder(tempFolder, "jnlpWarTmp_"
				+ System.currentTimeMillis());
		log("created rootfolder: " + rootFolder);
		
		try {
			File applicationFolder = createFolder(rootFolder, "application");
			File applicationNativeLibFolder = createFolder(applicationFolder, "nativelib");
			File iconFolder = createFolder(applicationFolder, "icon");
			File webinfFolder = createFolder(rootFolder, "WEB-INF");
			File webinflibFolder = createFolder(webinfFolder, "lib");
			//
			//copy icons, jars etc.. to the relevant folders
			//
			for (Icon icon : getIcons()) {
				StreamUtil.copyFile(icon.getFile(), iconFolder);
			}
			//jndc servlet -> web-inf/lib folder
			StreamUtil.copyFile("jnlp-servlet.jar", this.getClass().getClassLoader().getResourceAsStream(
					"org/unintelligible/antjnlpwar/resource/jnlp-servlet.jar"),
					webinflibFolder);

			//jars -> application folder
			for(WebstartLib lib : getLibraries()){
				FileScanner scanner = lib.getDirectoryScanner(getProject());
				String[] includedJars=scanner.getIncludedFiles();
				File basedir=scanner.getBasedir();
				for(String jarName : includedJars){
					File jar = new File(basedir, jarName);
					File copiedJar = new File(applicationFolder, jarName);
					log("copying jar " + jar);
					StreamUtil.copyFile(jar, applicationFolder);
					writeManifestAttributes(lib.getManifest(), copiedJar);
					expandedLibs.add(jar.getName());
				}
			}
			//main jar -> application folder
			StreamUtil.copyFile(getApplication().getJar(), applicationFolder);
			
			//native libs -> application/nativelib folder
			for(NativeLib nl : getNativeLibs()){
				String os = nl.getOs() == null ? "" : nl.getOs();
				String arch = nl.getArch() == null ? "" : nl.getArch();
				FileScanner scanner = nl.getDirectoryScanner(getProject());
				String[] includedJars=scanner.getIncludedFiles();
				File basedir=scanner.getBasedir();
				for(int i=0; i<includedJars.length;i++){
					//if the file extension is not .jar, pack as a jar
					File nativeLib=new File(basedir, includedJars[i]);
					if(!includedJars[i].endsWith(".jar")){
						log("Creating a jar file for native lib "+includedJars[i]);
						String osFile = os.replace(" ", "").replace("\\", "");
						String archFile = arch.replace(" ", "").replace("\\", "");
						File nativeLibJar=new File(applicationNativeLibFolder, includedJars[i] + "-" + osFile + "-" + archFile + ".jar");
						Zip jarTask=new Zip();
						jarTask.setProject(getProject());
						jarTask.setDestFile(nativeLibJar);
						jarTask.setBasedir(basedir);
						jarTask.setIncludes(includedJars[i]);
						jarTask.execute();
						//preserve datetime for versioning
						nativeLibJar.setLastModified(nativeLib.lastModified());
						expandedNativeLibs.add(nativeLibJar.getName());
						nativeJarOsMap.put(nativeLibJar.getName(), os);
						nativeJarArchMap.put(nativeLibJar.getName(), arch);
					} else {
						log("Copying native lib "+includedJars[i]);
						StreamUtil.copyFile(nativeLib, applicationNativeLibFolder);
						expandedNativeLibs.add(nativeLib.getName());
						nativeJarOsMap.put(nativeLib.getName(), os);
						nativeJarArchMap.put(nativeLib.getName(), arch);
					}
				}
			}
			//deal with pack200 and jar signing
			//if a storepass is specified, sign the jars
			if(getSignAlias()!=null){
				if(isPack200()){
					//http://java.sun.com/j2se/1.5.0/docs/guide/deployment/deployment-guide/pack200.html
					//we need to pack then unpack the files before signing them
					packJars(applicationFolder);
					unpackJars(applicationFolder);
					//REMOVE THIS if jnlp complains about unsigned jars (applicationFolder);
					//unpackJars(applicationFolder);
					
					packJars(applicationNativeLibFolder);
					unpackJars(applicationNativeLibFolder);
					//packJars(applicationNativeLibFolder);
					//unpackJars(applicationNativeLibFolder);
				}
				signJars(applicationFolder);
				signJars(applicationNativeLibFolder);
			}
			
			//if pack200, pack the files
			if(isPack200()){
				log("packing jars");
				packJars(applicationFolder);
				packJars(applicationNativeLibFolder);
			}
			//
			//template generation
			//
			//generate the JNLP deployment file
			log("os " + nativeJarOsMap);
			log("arch " + nativeJarArchMap);
			File jnlpOutputFile = new File(applicationFolder, "launch.jnlp");
			Generator jnlpGenerator = new Generator(this, jnlpOutputFile,
					"org/unintelligible/antjnlpwar/template/jnlp.vm");
			try {
				jnlpGenerator.generate();
			} catch (Exception e) {
				log(e.toString());
				throw new BuildException(
						"Could not generate the JNLP deployment descriptor", e);
			}
			//generate the web.xml file
			File webxmlOutputFile = new File(webinfFolder, "web.xml");

			Generator webxmlGenerator = new Generator(this, webxmlOutputFile,
					"org/unintelligible/antjnlpwar/template/webxml.vm");
			try {
				webxmlGenerator.generate();
			} catch (Exception e) {
				throw new BuildException("Could not generate the Web.xml file",
						e);
			}
			//generate the index.html file
			File indexhtmlOutputFile = new File(rootFolder, "index.html");

			Generator indexhtmlGenerator = new Generator(this, indexhtmlOutputFile,
					"org/unintelligible/antjnlpwar/template/indexhtml.vm");
			try {
				indexhtmlGenerator.generate();
			} catch (Exception e) {
				throw new BuildException("Could not generate the Index.html file",
						e);
			}
			//create the target war file
			/*
			War warTask=new War();
			warTask.setDestFile(getTofile());
			warTask.setWebxml(webxmlOutputFile);
			*/
			
			Jar warTask=new Jar();
			warTask.setProject(getProject());
			warTask.setDestFile(getTofile());
			warTask.setBasedir(rootFolder);
			warTask.setIncludes("**/*");
			log("about to call execute");
			warTask.execute();
			log("finished to call execute");
			
		}catch(Exception e){
			StringWriter sw = new StringWriter();
	        PrintWriter pw = new PrintWriter(sw, true);
	        e.printStackTrace(pw);
	        pw.flush();
	        sw.flush();
	        
			log("An error occurred during the task: "+sw.toString());
		} finally {
			//delete the temp folder
			Delete del = new Delete();
			del.setProject(getProject());
			del.setDir(rootFolder); 
			del.execute();
			
		}
	}

	
	private void writeManifestAttributes(Manifest mf, File jar) {
		Jar jarTask = new Jar();
		try {
			jarTask.addConfiguredManifest(mf);
		} catch (ManifestException e) {
			e.printStackTrace();
		}
		jarTask.setDestFile(jar);
		jarTask.setUpdate(true);
		jarTask.execute();
	}
	
	private void packJars(File folder){
		log("packJars for "+folder);
		Pack200Task packTask;
		File[] jarFiles=folder.listFiles(jarFileFilter);
		for(int i=0;i<jarFiles.length; i++){
			log("packJars: "+jarFiles[i]);
			File pack200Jar=new File(jarFiles[i].getParentFile(), jarFiles[i].getName()+".pack.gz");
			if(pack200Jar.exists()){
				pack200Jar.delete();
			}
			packTask=new Pack200Task();
			packTask.setProject(getProject());
			packTask.setDestfile(pack200Jar);
			packTask.setSrc(jarFiles[i]);
			packTask.setGZIPOutput(true);
			packTask.execute();
			pack200Jar.setLastModified(jarFiles[i].lastModified());
		}
	}
	private void unpackJars(File folder){
		Unpack200Task unpackTask;
		File[] packFiles=folder.listFiles(pack200FileFilter);
		for(int i=0;i<packFiles.length; i++){
			String jarFileName=packFiles[i].getAbsolutePath().substring(0, packFiles[i].getAbsolutePath().length()-8);
			File jarFile=new File(jarFileName);
			if(jarFile.exists()){
				jarFile.delete();
			}
			unpackTask=new Unpack200Task();
			unpackTask.setProject(getProject());
			unpackTask.setDest(jarFile);
			unpackTask.setSrc(packFiles[i]);
			unpackTask.execute();
			jarFile.setLastModified(packFiles[i].lastModified());
		}
	}
	private void signJars(File folder){
		File[] jarFiles=folder.listFiles(jarFileFilter);
		SignJar signJarTask;
		for(int i=0;i<jarFiles.length; i++){
			long lastModified=jarFiles[i].lastModified();
			signJarTask=new SignJar();
			signJarTask.setProject(getProject());
			signJarTask.setStorepass(getSignStorepass());
			signJarTask.setJar(jarFiles[i]);
			signJarTask.setAlias(getSignAlias());
			signJarTask.setKeystore(getKeystore());
			signJarTask.execute();
			jarFiles[i].setLastModified(lastModified);
		}
	}

	private void verifyInput() {
		if (getTofile() == null) {
			throw new BuildException("The tofile parameter is required");
		}
		if (getApplication() == null) {
			throw new BuildException("The application element is required");
		}
		if(getJ2ses().size()==0){
			addJ2se(new J2se());
		}
		//check required attributes in subelements
		for (Icon icon : getIcons()){
			if(icon.getFile()==null){
				throw new BuildException("The file attribute of the icon element is required");
			}
		}
		if(getApplication().getJar()==null){
			throw new BuildException("The jar attribute of the application element is required");
		}
	}

	private File createFolder(File parent, String folderName) {
		File folder = new File(parent, folderName);
		//need to check folder exists; if it does, delete contents
		if (folder.exists()) {
			Delete del = new Delete();
			del.setDir(folder);
			del.execute();
		}
		if (!folder.mkdir()) {
			throw new BuildException(
					"Could not create folder "
							+ folder.getAbsolutePath()
							+ ": does the current user have permission to write to the temp folder?");
		}
		return folder;
	}

}