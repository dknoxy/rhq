/*
 * RHQ Management Platform
 * Copyright (C) 2011 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */

package org.rhq.core.pc.drift;

import static org.rhq.common.drift.FileEntry.addedFileEntry;
import static org.rhq.common.drift.FileEntry.changedFileEntry;
import static org.rhq.common.drift.FileEntry.removedFileEntry;
import static org.rhq.core.domain.drift.DriftChangeSetCategory.COVERAGE;
import static org.rhq.core.domain.drift.DriftChangeSetCategory.DRIFT;
import static org.rhq.core.util.file.FileUtil.copyFile;
import static org.rhq.core.util.file.FileUtil.forEachFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.common.drift.ChangeSetReader;
import org.rhq.common.drift.ChangeSetWriter;
import org.rhq.common.drift.FileEntry;
import org.rhq.common.drift.Headers;
import org.rhq.core.domain.drift.DriftChangeSetCategory;
import org.rhq.core.domain.drift.DriftDefinition;
import org.rhq.core.domain.drift.Filter;
import org.rhq.core.util.MessageDigestGenerator;
import org.rhq.core.util.file.FileVisitor;

public class DriftDetector implements Runnable {
    private Log log = LogFactory.getLog(DriftDetector.class);

    private ScheduleQueue scheduleQueue;

    private ChangeSetManager changeSetMgr;

    private MessageDigestGenerator digestGenerator = new MessageDigestGenerator(MessageDigestGenerator.SHA_256);

    private DriftClient driftClient;

    public void setScheduleQueue(ScheduleQueue queue) {
        scheduleQueue = queue;
    }

    public void setChangeSetManager(ChangeSetManager changeSetManager) {
        changeSetMgr = changeSetManager;
    }

    public void setDriftClient(DriftClient driftClient) {
        this.driftClient = driftClient;
    }

    @Override
    public void run() {
        log.debug("Starting drift detection...");
        long startTime = System.currentTimeMillis();
        boolean updateSchedule = true;
        DriftDetectionSchedule schedule = null;
        try {
            if (log.isDebugEnabled()) {
                log.debug("Fetching next schedule from " + scheduleQueue);
            }

            schedule = scheduleQueue.getNextSchedule();
            if (schedule == null) {
                log.debug("No schedules are in the queue.");
                updateSchedule = false;
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug("Processing " + schedule);

            }

            if (schedule.getNextScan() > (System.currentTimeMillis() + 100L)) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping " + schedule + " because it is too early to do the next detection.");
                }
                updateSchedule = false;
                return;
            }

            if (!schedule.getDriftDefinition().isEnabled()) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping " + schedule + " because the drift definition is disabled.");
                }
                updateSchedule = false;
                return;
            }

            if (previousSnapshotExists(schedule)) {
                if (log.isDebugEnabled()) {
                    log.debug("Skipping " + schedule + " because server has not yet acked previous change set");
                }
                updateSchedule = false;
                return;
            }

            DriftDetectionSummary detectionSummary = new DriftDetectionSummary();
            detectionSummary.setSchedule(schedule);
            try {
                if (changeSetMgr.changeSetExists(schedule.getResourceId(), createHeaders(schedule, COVERAGE, 0))) {
                    detectionSummary.setType(DRIFT);
                    generateDriftChangeSet(detectionSummary);
                } else {
                    detectionSummary.setType(COVERAGE);
                    generateSnapshot(detectionSummary);
                }

                if (!detectionSummary.isBaseDirExists()) {
                    driftClient.reportMissingBaseDir(schedule.getResourceId(), schedule.getDriftDefinition());
                } else if (detectionSummary.isRepeat()) {
                    driftClient.repeatChangeSet(schedule.getResourceId(), schedule.getDriftDefinition().getName(),
                        detectionSummary.getVersion());
                } else if (changesNeedToBeReported(detectionSummary)) {
                    driftClient.sendChangeSetToServer(detectionSummary);
                }
            } catch (IOException e) {
                log.error("Drift detection failed: " + e.getMessage(), e);
                revertSnapshot(detectionSummary);
            } catch (RuntimeException e) {
                log.error("Drift detection failed: " + e.getMessage(), e);
                revertSnapshot(detectionSummary);
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause();
            String message = (null != cause) ? cause.getMessage() : t.getMessage();
            log.error("An unexpected error occurred during drift detection: " + message, t);

        } finally {
            try {
                scheduleQueue.deactivateSchedule(updateSchedule);
                long endTime = System.currentTimeMillis();
                log.debug("Finished drift detection in " + (endTime - startTime) + " ms");

            } catch (Throwable t) {
                Throwable cause = t.getCause();
                String message = (null != cause) ? cause.getMessage() : t.getMessage();
                log.error("An unexpected error occurred while deactivating schedule: " + message, t);
            }
        }
    }

    private boolean changesNeedToBeReported(DriftDetectionSummary detectionSummary) {
        return detectionSummary.getType() == COVERAGE || detectionSummary.getDriftChangeSet() != null;
    }

    private boolean previousSnapshotExists(DriftDetectionSchedule schedule) {
        File snapshot = changeSetMgr.findChangeSet(schedule.getResourceId(), schedule.getDriftDefinition().getName(),
            COVERAGE);
        File previousSnapshot = new File(snapshot.getParentFile(), snapshot.getName() + ".previous");
        return previousSnapshot.exists();
    }

    private void generateDriftChangeSet(DriftDetectionSummary summary) throws IOException {
        final DriftDetectionSchedule schedule = summary.getSchedule();

        log.debug("Generating drift change set for " + schedule);

        File currentSnapshot = changeSetMgr.findChangeSet(schedule.getResourceId(), schedule.getDriftDefinition()
            .getName(), COVERAGE);
        File snapshotFile = currentSnapshot;

        if (schedule.getDriftDefinition().isPinned()) {
            snapshotFile = new File(snapshotFile.getParentFile(), "snapshot.pinned");
        }

        final File basedir = new File(basedir(schedule.getResourceId(), schedule.getDriftDefinition()));
        final Set<File> processedFiles = new HashSet<File>();
        final List<FileEntry> snapshotEntries = new LinkedList<FileEntry>();
        final List<FileEntry> deltaEntries = new LinkedList<FileEntry>();

        ChangeSetReader coverageReader = null;
        int newVersion;
        try {
            coverageReader = changeSetMgr.getChangeSetReader(snapshotFile);

            if (!basedir.exists()) {
                log.warn("The base directory [" + basedir.getAbsolutePath() + "] for " + schedule + " does not exist.");
            }

            if (schedule.getDriftDefinition().isPinned()) {
                ChangeSetReader snapshotReader = null;
                try {
                    snapshotReader = changeSetMgr.getChangeSetReader(currentSnapshot);
                    newVersion = snapshotReader.getHeaders().getVersion() + 1;
                } finally {
                    snapshotReader.close();
                }
            } else {
                newVersion = coverageReader.getHeaders().getVersion() + 1;
            }

            // First look for files that have either been modified or deleted
            scanForModifiedOrDeletedFiles(schedule, basedir, processedFiles, snapshotEntries, deltaEntries,
                coverageReader);
        } finally {
            coverageReader.close();
        }

        // If the basedir is still valid we need to do a directory tree scan to look for newly added files
        if (basedir.isDirectory()) {
            DriftDefinition driftDef = schedule.getDriftDefinition();
            List<Filter> includes = driftDef.getIncludes();
            List<Filter> excludes = driftDef.getExcludes();

            for (File dir : getScanDirectories(basedir, includes)) {
                forEachFile(dir, new FilterFileVisitor(basedir, includes, excludes, new FileVisitor() {
                    @Override
                    public void visit(File file) {
                        try {
                            if (processedFiles.contains(file)) {
                                return;
                            }

                            if (!file.canRead()) {
                                if (log.isDebugEnabled()) {
                                    log.debug("Skipping " + file.getPath() + " since it is not readable.");
                                }
                                return;
                            }

                            if (log.isInfoEnabled()) {
                                log.info("Detected added file for " + schedule + " --> " + file.getAbsolutePath());
                            }

                            FileEntry addedFileEntry = getAddedFileEntry(basedir, file);
                            if (null != addedFileEntry) {
                                deltaEntries.add(addedFileEntry);
                                snapshotEntries.add(addedFileEntry);
                            }
                        } catch (Throwable t) {
                            // report the error but keep going, perhaps it is specific to a single file, try to
                            // finish the change set generation.
                            log.error("An unexpected error occurred while generating a drift change set for file "
                                + file.getPath() + " in schedule " + schedule + ". Skipping file.", t);
                        }
                    }
                }));
            }
        }

        if (deltaEntries.isEmpty()) {
            summary.setNewSnapshot(currentSnapshot);

            // If nothing has changed, there is no need to add/update any files
            // unless the definition is pinned in which case we need to reset
            // the current snapshot to match the pinned snapshot. Note though
            // that we increment the snapshot version in order to let the server
            // know about the state change.
            if (schedule.getDriftDefinition().isPinned() && newVersion > 1
                && !isPreviousChangeSetEmpty(schedule.getResourceId(), schedule.getDriftDefinition())) {
                currentSnapshot.delete();
                File newSnapshot = updateCurrentSnapshot(schedule, snapshotEntries, newVersion);

                updateDeltaSnapshot(summary, schedule, deltaEntries, newVersion, currentSnapshot, newSnapshot);
                // TODO report back to the server that we are back in compliance
            }
        } else {
            if (schedule.getDriftDefinition().isPinned() && newVersion > 1
                && isSameAsPreviousChangeSet(deltaEntries, currentSnapshot)) {
                // if we are still out of compliance just report, we report a
                // repeat change set to indicate no changes but also still out
                // of compliance.
                summary.setVersion(newVersion - 1);
                summary.setRepeat(true);
                return;
            }

            File oldSnapshot = backupAndDeleteCurrentSnapshot(currentSnapshot);
            File newSnapshot = updateCurrentSnapshot(schedule, snapshotEntries, newVersion);

            updateDeltaSnapshot(summary, schedule, deltaEntries, newVersion, oldSnapshot, newSnapshot);
        }
    }

    /**
     * File.canRead() is basically a security check and does not guarantee that the file contents can truly be read.
     * Certain files, like socket files on linux, can not be processed and it's not known until actually trying to
     * construct a FileInputStream, as is done when we actually try to generate the digest. These files will generate 
     * a FileNotFoundException. This method will catch, log and suppress that issue, and return null
     * indicating the file is not suitable for drift detection.
     * 
     * @param basedir the drift def base directory
     * @param file the new file to add
     * @return the new FileEntry, or null if this file is not appropriate for drift detection (typically if the
     * underlying file does not support the needed File operations.
     * @throws Will throw unexpected IOExceptions, outside of the FileNotFoundException it looks for. 
     */
    private FileEntry getAddedFileEntry(File basedir, File file) throws IOException {
        FileEntry result = null;

        try {
            String sha256 = sha256(file);
            String relativePath = relativePath(basedir, file);

            result = addedFileEntry(relativePath, sha256);

        } catch (FileNotFoundException e) {
            if (log.isDebugEnabled()) {
                log.debug("Skipping " + file.getPath() + " since it is missing or is not a physically readable file.");
            }
        }

        return result;
    }

    private Set<File> getScanDirectories(final File basedir, List<Filter> includes) {

        Set<File> directories = new HashSet<File>();

        if (null == includes || includes.isEmpty()) {
            directories.add(basedir);
        } else {
            for (Filter filter : includes) {
                String path = filter.getPath();
                if (".".equals(path)) {
                    directories.add(basedir);
                } else {
                    directories.add(new File(basedir, path));
                }
            }
        }

        return directories;
    }

    private void scanForModifiedOrDeletedFiles(DriftDetectionSchedule schedule, File basedir, Set<File> processedFiles,
        List<FileEntry> snapshotEntries, List<FileEntry> deltaEntries, ChangeSetReader coverageReader)
        throws IOException {

        for (FileEntry entry : coverageReader) {
            File file = new File(basedir, entry.getFile());
            if (!file.exists()) {
                // The file has been deleted since the last scan
                if (log.isDebugEnabled()) {
                    log.debug("Detected deleted file for " + schedule + " --> " + file.getAbsolutePath());
                }
                deltaEntries.add(removedFileEntry(entry.getFile(), entry.getNewSHA()));
            } else if (!file.canRead()) {
                processedFiles.add(file);
                if (log.isDebugEnabled()) {
                    log.debug(file.getPath() + " is no longer readable. Treating it as a deleted file.");
                }
                deltaEntries.add(removedFileEntry(entry.getFile(), entry.getNewSHA()));
            } else {
                processedFiles.add(file);
                String currentSHA = sha256(file);
                if (!currentSHA.equals(entry.getNewSHA())) {
                    if (log.isDebugEnabled()) {
                        log.debug("Detected modified file for " + schedule + " --> " + file.getAbsolutePath());
                    }
                    FileEntry modifiedEntry = changedFileEntry(entry.getFile(), entry.getNewSHA(), currentSHA);
                    deltaEntries.add(modifiedEntry);
                    snapshotEntries.add(modifiedEntry);
                } else {
                    // The file has not changed
                    snapshotEntries.add(entry);
                }
            }
        }
    }

    private boolean isPreviousChangeSetEmpty(int resourceId, DriftDefinition definition) throws IOException {
        File changeSet = changeSetMgr.findChangeSet(resourceId, definition.getName(), DRIFT);
        if (!changeSet.exists()) {
            return true;
        }
        ChangeSetReader reader = null;
        boolean isEmpty;
        try {
            reader = changeSetMgr.getChangeSetReader(changeSet);
            isEmpty = true;

            for (FileEntry entry : reader) {
                isEmpty = false;
                break;
            }
        } finally {
            if (reader != null) {
                reader.close();
            }
        }

        return isEmpty;
    }

    private void updateDeltaSnapshot(DriftDetectionSummary summary, DriftDetectionSchedule schedule,
        List<FileEntry> deltaEntries, int newVersion, File oldSnapshot, File newSnapshot) throws IOException {

        ChangeSetWriter deltaWriter = null;

        try {
            Headers deltaHeaders = createHeaders(schedule, DRIFT, newVersion);
            File driftChangeSet = changeSetMgr.findChangeSet(schedule.getResourceId(), schedule.getDriftDefinition()
                .getName(), DRIFT);
            deltaWriter = changeSetMgr.getChangeSetWriter(driftChangeSet, deltaHeaders);

            summary.setDriftChangeSet(driftChangeSet);
            summary.setNewSnapshot(newSnapshot);
            summary.setOldSnapshot(oldSnapshot);

            for (FileEntry entry : deltaEntries) {
                deltaWriter.write(entry);
            }
        } finally {
            if (deltaWriter != null) {
                deltaWriter.close();
            }
        }
    }

    private File backupAndDeleteCurrentSnapshot(File currentSnapshot) throws IOException {
        File oldSnapshot = new File(currentSnapshot.getParentFile(), currentSnapshot.getName() + ".previous");
        copyFile(currentSnapshot, oldSnapshot);
        currentSnapshot.delete();
        return oldSnapshot;
    }

    private File updateCurrentSnapshot(DriftDetectionSchedule schedule, List<FileEntry> snapshotEntries, int newVersion)
        throws IOException {

        ChangeSetWriter newSnapshotWriter = null;

        try {
            Headers snapshotHeaders = createHeaders(schedule, COVERAGE, newVersion);
            File newSnapshot = changeSetMgr.findChangeSet(schedule.getResourceId(), schedule.getDriftDefinition()
                .getName(), COVERAGE);
            newSnapshotWriter = changeSetMgr.getChangeSetWriter(schedule.getResourceId(), snapshotHeaders);

            for (FileEntry entry : snapshotEntries) {
                newSnapshotWriter.write(entry);
            }
            return newSnapshot;
        } finally {
            newSnapshotWriter.close();
        }
    }

    private boolean isSameAsPreviousChangeSet(List<FileEntry> entries, File currentSnapsotFile) throws IOException {
        HashMap<String, FileEntry> entriesMap = new HashMap<String, FileEntry>();
        for (FileEntry e : entries) {
            entriesMap.put(e.getFile(), e);
        }

        ChangeSetReader reader = null;
        try {
            File deltaChangeSet = new File(currentSnapsotFile.getParentFile(), "drift-changeset.txt");
            reader = changeSetMgr.getChangeSetReader(deltaChangeSet);

            int numEntries = 0;
            for (FileEntry entry : reader) {
                FileEntry newEntry = entriesMap.get(entry.getFile());
                if (newEntry == null) {
                    return false;
                }
                if (entry.getType() != newEntry.getType()) {
                    return false;
                }
                switch (entry.getType()) {
                case FILE_ADDED:
                    if (!entry.getNewSHA().equals(newEntry.getNewSHA())) {
                        return false;
                    }
                case FILE_CHANGED:
                    if (!entry.getNewSHA().equals(newEntry.getNewSHA())
                        || !entry.getOldSHA().equals(newEntry.getOldSHA())) {
                        return false;
                    }
                default: // FILE_REMOVED
                    if (!entry.getOldSHA().equals(newEntry.getOldSHA())) {
                        return false;
                    }
                }
                numEntries++;
            }

            return numEntries == entriesMap.size();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private void generateSnapshot(DriftDetectionSummary summary) throws IOException {
        final DriftDetectionSchedule schedule = summary.getSchedule();
        final DriftDefinition driftDef = schedule.getDriftDefinition();
        final File basedir = new File(basedir(schedule.getResourceId(), driftDef));

        if (!basedir.exists()) {
            if (log.isWarnEnabled()) {
                log.warn("The base directory [" + basedir.getAbsolutePath() + "] for " + schedule + " does not "
                    + "exist. You may want review the drift definition and verify that the value of the base "
                    + "directory is in fact correct.");
            }
            summary.setBaseDirExists(false);
            return;
        }

        log.debug("Generating coverage change set for " + schedule);

        File snapshot = changeSetMgr.findChangeSet(schedule.getResourceId(), schedule.getDriftDefinition().getName(),
            COVERAGE);

        ChangeSetWriter writer = null;
        try {
            writer = changeSetMgr.getChangeSetWriter(snapshot, createHeaders(schedule, COVERAGE, 0));

            if (basedir.isDirectory()) {
                doDirectoryScan(schedule, driftDef, basedir, writer);
                writer.close();
                writer = null;
            }
            if (schedule.getDriftDefinition().isPinned()) {
                copyFile(snapshot, new File(snapshot.getParentFile(), "snapshot.pinned"));
            }
            summary.setNewSnapshot(snapshot);
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    private void doDirectoryScan(final DriftDetectionSchedule schedule, DriftDefinition driftDef, final File basedir,
        final ChangeSetWriter writer) {

        List<Filter> includes = driftDef.getIncludes();
        List<Filter> excludes = driftDef.getExcludes();

        for (File dir : getScanDirectories(basedir, includes)) {
            forEachFile(dir, new FilterFileVisitor(basedir, includes, excludes, new FileVisitor() {
                @Override
                public void visit(File file) {
                    try {
                        if (!file.canRead()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Skipping " + file.getPath() + " since we do not have read access.");
                            }
                            return;
                        }

                        if (log.isDebugEnabled()) {
                            log.debug("Adding " + file.getPath() + " to coverage change set for " + schedule);
                        }

                        FileEntry addedFileEntry = getAddedFileEntry(basedir, file);
                        if (null != addedFileEntry) {
                            writer.write(addedFileEntry);
                        }

                    } catch (Throwable t) {
                        // report the error but keep going, perhaps it is specific to a single file, try to
                        // finish the detection.
                        log.error("An unexpected error occurred while generating a coverage change set for file "
                            + file.getPath() + " in schedule " + schedule + ". Skipping file.", t);
                    }
                }
            }));
        }
    }

    private String relativePath(File basedir, File file) {
        if (basedir.equals(file)) {
            return ".";
        }
        String filePath = file.getAbsolutePath();
        String basedirPath = basedir.getAbsolutePath();
        int basedirLen = basedirPath.length();
        if (!basedirPath.endsWith(File.separator)) {
            ++basedirLen;
        }
        return filePath.substring(basedirLen);
    }

    private String sha256(File file) throws IOException {
        return digestGenerator.calcDigestString(file);
    }

    private String basedir(int resourceId, DriftDefinition driftDef) {
        return driftClient.getAbsoluteBaseDirectory(resourceId, driftDef).getAbsolutePath();
    }

    private Headers createHeaders(DriftDetectionSchedule schedule, DriftChangeSetCategory type, int version) {
        Headers headers = new Headers();
        headers.setResourceId(schedule.getResourceId());
        headers.setDriftDefinitionId(schedule.getDriftDefinition().getId());
        headers.setDriftDefinitionName(schedule.getDriftDefinition().getName());
        headers.setBasedir(basedir(schedule.getResourceId(), schedule.getDriftDefinition()));
        headers.setType(type);
        headers.setVersion(version);

        return headers;
    }

    private void revertSnapshot(DriftDetectionSummary summary) throws IOException {
        log.info("Reverting snapshot for " + summary.getSchedule());

        DriftDetectionSchedule scheudle = summary.getSchedule();
        File newSnapshot = changeSetMgr.findChangeSet(scheudle.getResourceId(),
            scheudle.getDriftDefinition().getName(), COVERAGE);

        // We want to delete the snapshot file regardless of whether the drift detection
        // was for an initial coverage change set or for a drift change set. We do not know
        // the state of the snapshot file so we have to delete it. If we have only generated
        // the initial coverage change set, then it will get regenerated.
        newSnapshot.delete();

        if (summary.getType() == DRIFT) {
            File oldSnapshotBackup = summary.getOldSnapshot();
            // If we generated a drift change set, we need to check for a back up of the
            // previous snapshot. We revert to the back up. If no back up is found, we log
            // an error.
            if (oldSnapshotBackup != null && oldSnapshotBackup.exists()) {
                copyFile(oldSnapshotBackup, newSnapshot);
                // We have to delete to the previous version snapshot file; otherwise,
                // subsequent detection runs will be skipped.
                oldSnapshotBackup.delete();
            } else {
                // TODO Should we throw an exception and/or disable detection?
                // If we fall into this else block, that means we were not able to revert
                // to the previous snapshot version, and we may be in an inconsistent state.
                log.error("Cannot revert snapshot to previous version for " + summary.getSchedule()
                    + ". Snapshot back up file not found.");
            }
        }
        deleteZipFiles(newSnapshot.getParentFile());
    }

    private void deleteZipFiles(File dir) {
        File[] files = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".zip");
            }
        });
        for (File file : files) {
            file.delete();
        }
    }

}