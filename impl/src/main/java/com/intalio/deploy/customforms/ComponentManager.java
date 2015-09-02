/**
 * Copyright (C) 1999-2015, Intalio Inc.
 *
 * The program(s) herein may be used and/or copied only with
 * the written permission of Intalio Inc. or in accordance with
 * the terms and conditions stipulated in the agreement/contract
 * under which the program(s) have been supplied.
 */

package com.intalio.deploy.customforms;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.intalio.deploy.deployment.ComponentId;
import org.intalio.deploy.deployment.DeploymentMessage;
import org.intalio.deploy.deployment.DeploymentMessage.Level;
import org.intalio.deploy.deployment.spi.ComponentManagerResult;

/**
 * The ComponentManager for deploying the .customforms component of deployment
 * bundle
 * 
 * @author Cyril Antony
 */
public class ComponentManager implements
        org.intalio.deploy.deployment.spi.ComponentManager {

    private static final Log LOG = LogFactory.getLog(ComponentManager.class);

    private File customformsDir;
    private File customformsRepo;

    public void activate(ComponentId id, File path, List<String> resources) {
    }

    public void activateProcess(ComponentId id, File path,
            List<String> resources, String formURL) {
    }

    public void activated(ComponentId id, File path, List<String> resources) {
    }

    protected String resolvePackageNameFromComponentId(ComponentId name) {
        return name.getAssemblyId() + "/" + name.getComponentName();
    }

    public ComponentManagerResult deploy(ComponentId id, File path,
            boolean activate) {
        List<DeploymentMessage> messages = new ArrayList<DeploymentMessage>();
        List<String> deployedResources = new ArrayList<String>();
        String duName = resolvePackageNameFromComponentId(id);
        deployedResources.add(duName);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Deploy: " + id + " Path: " + path);
        }
        String packageName = id.getAssemblyId().getAssemblyName();
        String version = getVersion(id);
        try {
            File cfDir = copyCustomforms(path, getCustomformsDir(),
                    packageName, version);
            File cfRepo = copyCustomforms(path, getCustomformsRepo(),
                    packageName, version);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Customforms content copied to : \n" + cfDir + ",\n"
                        + cfRepo);
            }
        } catch (Exception e) {
            LOG.error("Deployment of " + path.getAbsolutePath()
                    + " failed, aborting for now.", e);
            StringWriter errors = new StringWriter();
            e.printStackTrace(new PrintWriter(errors));
            messages.add(new DeploymentMessage(Level.ERROR, errors.toString()));
        }
        return new ComponentManagerResult(messages, deployedResources);
    }

    private File copyCustomforms(File fromDir, File toDir, String packageName,
            String version) throws IOException {

        File cformsDir = formChildFile(toDir, packageName, version);
        if (!cformsDir.exists()) {
            cformsDir.mkdirs();
        }
        FileUtils.copyDirectory(fromDir, cformsDir);
        return cformsDir;
    }

    private File formChildFile(File parent, String... children) {
        File file = parent;
        for (int i = 0; i < children.length; i++) {
            file = new File(file, children[i]);
        }
        return file;
    }

    public void deployed(ComponentId name, File path, List<String> resources,
            boolean active) {
        String packageName = name.getAssemblyId().getAssemblyName();
        String version = getVersion(name);

        File cfDir = formChildFile(getCustomformsDir(), packageName, version);
        if (!cfDir.exists()) {
            try {
                cfDir = copyCustomforms(path, getCustomformsDir(), packageName,
                        version);
                File cfRepo = copyCustomforms(path, getCustomformsRepo(),
                        packageName, version);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Customforms content copied to : \n" + cfDir
                            + ",\n" + cfRepo);
                }
            } catch (Exception e) {
                LOG.error(
                        "This server node was notified about deployment of customforms of Component:"
                                + name
                                + " and resource:"
                                + path
                                + ". But deployment failed in this node. It has succeeded in other node in cluster",
                        e);
            }
        }
    }

    public void dispose(ComponentId id, File path, List<String> resources,
            boolean active) {
    }

    public void initialize(ComponentId id, File path, List<String> resources,
            boolean active) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("CUSTOMFORMS DEPLOY: initialize() for :" + id);
        }
        restoreFromRepo(id);
    }

    private void restoreFromRepo(ComponentId id) {
        String packageName = id.getAssemblyId().getAssemblyName();
        String version = getVersion(id);
        File srcDir = formChildFile(getCustomformsRepo(), packageName, version);
        File destDir = formChildFile(getCustomformsDir(), packageName, version);
        if (!destDir.exists()) {
            if (!srcDir.exists()) {
                LOG.error("Content not available in the intalio-customforms repository for Component:"
                        + id + ". Unable to deploy to live server");
                return;
            }
            try {
                FileUtils.copyDirectory(srcDir, destDir);
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Deployed customforms content From:" + srcDir
                            + "   To:" + destDir);
                }
            } catch (Exception e) {
                LOG.error(
                        "Failed to deploy content from customforms-backup-repo to live server for ComponentID :"
                                + id, e);
                throw new RuntimeException(
                        "Failed to deploy content from customforms-backup-repo to live server.",
                        e);
            }
        }
    }

    private File getCustomformsRepo() {
        if (null == customformsRepo) {
            String property = System
                    .getProperty("com.intalio.bpms.customforms.repository");
            if (null == property) {
                throw new RuntimeException("Property '"
                        + "com.intalio.bpms.customforms.repository"
                        + "' is not configured in base-config.properties");
            }
            File cfRepo = new File(property);
            cfRepo.mkdirs();
            if (!cfRepo.exists()) {
                LOG.error("Not able to create customforms repository." + cfRepo);
                throw new RuntimeException(
                        "Not able to create customforms repository. "
                                + "Please check for file system access permissions.");
            }
            customformsRepo = cfRepo;
            if (LOG.isDebugEnabled()) {
                LOG.debug("intalio-customforms repository is configured as :"
                        + customformsRepo);
            }
        }
        return customformsRepo;
    }

    public void retire(ComponentId id, File path, List<String> resources) {
    }

    public void retired(ComponentId id, File path, List<String> resources) {
    }

    public void start(ComponentId id, File path, List<String> resources,
            boolean active) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Start: " + id);
        }
    }

    public void stop(ComponentId id, File path, List<String> resources,
            boolean active) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Stop: " + id);
        }
    }

    public void undeploy(ComponentId componentId, File file,
            List<String> resources, boolean active) {
        undeploy(componentId, file, resources);
    }

    public void undeploy(ComponentId id, File file, List<String> resources) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Undeploy: " + id);
        }
        String packageName = id.getAssemblyId().getAssemblyName();
        String version = getVersion(id);
        try {
            File cfDir = deleteCustomforms(getCustomformsDir(), packageName,
                    version);
            File cfRepo = deleteCustomforms(getCustomformsRepo(), packageName,
                    version);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Deleted customforms content from :\n" + cfRepo
                        + ",\n" + cfDir);
            }
        } catch (Exception e) {
            LOG.error("Undeployment of customforms failed for component:" + id,
                    e);
        }

    }

    private File deleteCustomforms(File parentDir, String packageName,
            String version) throws IOException {
        File packageDir = formChildFile(parentDir, packageName);
        if (packageDir.exists()) {
            File versionDir = formChildFile(packageDir, version);
            if (versionDir.exists()) {
                FileUtils.deleteDirectory(versionDir);
            }
            String[] list = packageDir.list();
            if (null == list || list.length == 0) {
                FileUtils.deleteDirectory(packageDir);
                return packageDir;
            }
            return versionDir;
        }
        return null;
    }

    private String getVersion(ComponentId id) {
        Integer v = id.getAssemblyId().getAssemblyVersion();
        return v == 0 ? "1" : v.toString();
    }

    public void undeployed(ComponentId id, File path, List<String> resources) {
        String packageName = id.getAssemblyId().getAssemblyName();
        String version = getVersion(id);
        try {
            File cfDir = deleteCustomforms(getCustomformsDir(), packageName,
                    version);
            File cfRepo = deleteCustomforms(getCustomformsRepo(), packageName,
                    version);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Deleted customforms content from :\n" + cfRepo
                        + ",\n" + cfDir);
            }
        } catch (Exception e) {
            LOG.error("Undeployment of " + path.getAbsolutePath() + " failed",
                    e);
        }

    }

    public String getComponentManagerName() {
        return "customforms";
    }

    public void retireProcess(ComponentId arg0, File arg1, List<String> arg2,
            String arg3) {
    }

    public File getCustomformsDir() {
        if (null == customformsDir) {
            String intalioCtxtPath = System.getProperty("intalio.contextPath");
            File intalioCtxt = new File(intalioCtxtPath);
            String customformsRelUrl = System
                    .getProperty("com.intalio.bpms.customforms.url");
            File cfDir = formChildFile(intalioCtxt, customformsRelUrl);
            cfDir.mkdirs();
            if (!cfDir.exists()) {
                LOG.error("Not able to create customforms directory inside intalio webapp context :"
                        + cfDir);
                throw new RuntimeException(
                        "Not able to create customforms directory inside intalio webapp context. "
                                + "Please check for file system access permissions.");
            }
            customformsDir = cfDir;
            if (LOG.isDebugEnabled()) {
                LOG.debug("intalio-customforms deployment directory is configured as :"
                        + customformsDir);
            }

        }
        return customformsDir;
    }

}