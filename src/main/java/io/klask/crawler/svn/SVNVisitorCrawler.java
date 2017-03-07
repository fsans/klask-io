package io.klask.crawler.svn;

import io.klask.crawler.impl.SVNCrawler;
import io.klask.domain.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNProperty;
import org.tmatesoft.svn.core.SVNPropertyValue;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.diff.SVNDeltaProcessor;
import org.tmatesoft.svn.core.io.diff.SVNDiffWindow;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

/**
 * Created by harelj on 06/03/2017.
 */
public class SVNVisitorCrawler implements ISVNEditor {
    private final Logger log = LoggerFactory.getLogger(SVNVisitorCrawler.class);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    File currentFile = new File();
    private Stack<String> myDirectoriesStack = new Stack<>();
    private Map myDirProps = new HashMap();
    private Map myFileProps = new HashMap();
    private boolean skip=false;
    private boolean currentFileReadable = false;
    private boolean currentFileExcluded = false;
    private SVNDeltaProcessor myDeltaProcessor = new SVNDeltaProcessor();
    private SVNCrawler svnCrawler;

    //if crawler get 'trunk', 'tags' or 'branches' the currentProject is the directory just above
    private String currentProject = null;
    //if crawler get 'trunk', 'tags' or 'branches' the currentBranch is the directory just below
    private String currentBranch = null;

    public SVNVisitorCrawler(SVNCrawler svnCrawler){
        this.svnCrawler = svnCrawler;
    }

    @Override
    public void abortEdit() throws SVNException {
        //log.trace("abortEdit");
    }

    @Override
    public void absentDir(String path) throws SVNException {
        //log.trace("absentDir {}", path);
    }

    @Override
    public void absentFile(String path) throws SVNException {
        //log.trace("absentFile {}", path);
    }

    @Override
    public void addFile(String path, String copyFromPath, long copyFromRevision) throws SVNException {
        if(skip)return;
        log.debug("addFile {}", path);
        outputStream.reset();
        currentFileReadable = this.svnCrawler.isReadableExtension(path);
        currentFileExcluded = this.svnCrawler.isFileInExclusion(path);
        if (!currentFileExcluded) {
            currentFile = this.svnCrawler.createFile(path);
        } else {
            currentFile = null;
        }
    }

    @Override
    public SVNCommitInfo closeEdit() throws SVNException {
        //log.debug("closeEdit");
        return null;
    }

    //in the closeFile, the md5Checksum give the MD5 check sum
    @Override
    public void closeFile(String path, String md5Checksum) throws SVNException {
        //log.debug("closeFile {}:{}",path,md5Checksum);
        if(skip)return;
        if (currentFileExcluded) {
            return;
        }
        if (currentFileReadable) {
            currentFile.setContent(new String(outputStream.toByteArray(), Charset.forName("iso-8859-1")));
            currentFile.setSize((long) outputStream.size());//TODO fix the int => long problem
        }
        currentFile.setProject(currentProject);
        currentFile.setVersion(currentBranch);
        this.svnCrawler.addFile(currentFile);

    }

    @Override
    public void deleteEntry(String path, long revision) throws SVNException {
        //log.debug("deleteEntry {} : {}", path, revision);
    }

    @Override
    public void openFile(String path, long revision) throws SVNException {
        ////log.debug("openFile {}:{}",path,revision);
    }

    @Override
    public void targetRevision(long revision) throws SVNException {
        //log.debug("targetRevision {}", revision);
    }

    @Override
    public void applyTextDelta(String path, String baseChecksum) throws SVNException {
        //log.trace("applyTextDelta {} ck {}", path, baseChecksum);
        if(skip)return;
        if (!currentFileExcluded && currentFileReadable) {
            myDeltaProcessor.applyTextDelta(null, outputStream, false);
        }
    }

    @Override
    public OutputStream textDeltaChunk(String path, SVNDiffWindow diffWindow) throws SVNException {
        //log.trace("textDeltaChunk {}:{}",path,diffWindow);
        if(skip)return null;
        if (currentFileExcluded || !currentFileReadable) {
            return SVNFileUtil.DUMMY_OUT;
        }
        return myDeltaProcessor.textDeltaChunk( diffWindow );


//        try {
//            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//            diffWindow.writeTo(outputStream, false, true);
//
//            currentFile.setContent(new String(outputStream.toByteArray(), Charset.forName("iso-8859-1")));
//            currentFile.setSize((long)diffWindow.getDataLength());//TODO fix the int => long problem
//            return outputStream;
//        }
//        catch(Exception e){
//            log.error("read failed on {}",path, e);
//        }
//
//        return null;
    }

    @Override
    public void textDeltaEnd(String path) throws SVNException {
        //log.trace("textDeltaEnd {}", path);
        if(skip)return;
        if (!currentFileExcluded && currentFileReadable) {
            myDeltaProcessor.textDeltaEnd();
        }
    }

    @Override
    public void addDir(String path, String copyFromPath, long copyFromRevision) throws SVNException {
        //log.trace("addDir {}", path);
        if (path != null) {
            if (path.endsWith("tags")) {
                skip = true;
            }
            if (currentProject == null && (path.endsWith("trunk") || path.endsWith("branches"))) {
                String lastDir = myDirectoriesStack.peek();
                this.currentProject = lastDir.substring(lastDir.lastIndexOf('/') + 1);
            }

            if (myDirectoriesStack.peek().endsWith("branches")) {
                currentBranch = path.substring(path.lastIndexOf('/') + 1);
            }
            if (myDirectoriesStack.peek().endsWith("trunk")) {
                currentBranch = "trunk";
            }

        }

        String absoluteDirPath = "/" + path;
        myDirectoriesStack.push(absoluteDirPath);
    }

    @Override
    public void changeDirProperty(String name, SVNPropertyValue value) throws SVNException {
        //filter out svn:entry and svn:wc properties since we are interested in regular properties only
//        if (!SVNProperty.isRegularProperty(name)) {
//            return;
//        }
        if(skip)return;

        String currentDirPath = myDirectoriesStack.peek();
        Map props = (Map) myDirProps.get(currentDirPath);
        if (props == null) {
            props = new HashMap();
            myDirProps.put(currentDirPath, props);
        }
        props.put(name, value);
    }

    @Override
    public void changeFileProperty(String path, String propertyName, SVNPropertyValue propertyValue) throws SVNException {
        //log.trace("property {} : {}",propertyName,propertyValue);
        //filter out svn:entry and svn:wc properties since we are interested in regular properties only
//        if (!SVNProperty.isRegularProperty(propertyName)) {
//            return;
//        }
        if(skip) return;

        if(SVNProperty.isSVNKitProperty(propertyName)){
            log.trace("sha1 {}", propertyName);
        }
        if("svn:entry:last-author".equals(propertyName) && currentFile!=null){
            currentFile.setLastAuthor(propertyValue.getString());
        }
        //svn:entry:committed-rev
        //svn:mime-type

        if ("svn:entry:committed-date".equals(propertyName) && currentFile != null) {
            currentFile.setLastDate(propertyValue.getString());
        }


        String absolutePath = "/" + path;
        Map props = (Map) myFileProps.get(absolutePath);
        if (props == null) {
            props = new HashMap();
            myFileProps.put(absolutePath, props);
        }
        props.put(propertyName, propertyValue);
    }

    @Override
    public void closeDir() throws SVNException {
        //log.trace("closeDir");
        String last = myDirectoriesStack.pop();
        if (last != null) {
            if (last.endsWith("tags")) {
                skip = false;
            }
            if (last.endsWith("branches") || last.endsWith("trunk")) {
                currentProject = null;
                currentBranch = null;
            }
        }


    }

    @Override
    public void openDir(String path, long revision) throws SVNException {
        //log.trace("openDir {} : {}",path,revision);
        String absoluteDirPath = "/" + path;
        myDirectoriesStack.push(absoluteDirPath);
    }

    @Override
    public void openRoot(long revision) throws SVNException {
        //log.trace("openRoot : {}",revision);
        String absoluteDirPath = this.svnCrawler.getRepository().getPath();
        myDirectoriesStack.push(absoluteDirPath);
    }

    public Map getDirsToProps() {
        return myDirProps;
    }

    public Map getFilesToProps() {
        return myFileProps;
    }
}
