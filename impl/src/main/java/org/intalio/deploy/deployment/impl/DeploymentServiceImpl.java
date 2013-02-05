/**
 * Copyright (c) 2005-2010 Intalio inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * Intalio inc. - initial API and implementation
 */
package org.intalio.deploy.deployment.impl;

import static org.intalio.deploy.deployment.impl.LocalizedMessages._;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.rmi.Remote;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

import org.intalio.deploy.deployment.AssemblyId;
import org.intalio.deploy.deployment.ComponentId;
import org.intalio.deploy.deployment.DeployedAssembly;
import org.intalio.deploy.deployment.DeployedComponent;
import org.intalio.deploy.deployment.DeploymentMessage;
import org.intalio.deploy.deployment.DeploymentResult;
import org.intalio.deploy.deployment.DeploymentService;
import org.intalio.deploy.deployment.DeploymentMessage.Level;
import org.intalio.deploy.deployment.impl.DeployMBeanServer.NullDeployMBeanServer;
import org.intalio.deploy.deployment.impl.clustering.ActivatedMessage;
import org.intalio.deploy.deployment.impl.clustering.Cluster;
import org.intalio.deploy.deployment.impl.clustering.ClusterListener;
import org.intalio.deploy.deployment.impl.clustering.DeployedMessage;
import org.intalio.deploy.deployment.impl.clustering.RetiredMessage;
import org.intalio.deploy.deployment.impl.clustering.SingleNodeCluster;
import org.intalio.deploy.deployment.impl.clustering.UndeployedMessage;
import org.intalio.deploy.deployment.spi.ComponentManager;
import org.intalio.deploy.deployment.spi.ComponentManagerLockAware;
import org.intalio.deploy.deployment.spi.ComponentManagerResult;
import org.intalio.deploy.deployment.spi.DeploymentServiceCallback;
import org.intalio.deploy.registry.RemoteProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.util.SystemPropertyUtils;

/**
 * Deployment service
 */
@ManagedResource(objectName="intalio:module=DeploymentService,service=DeploymentService")
public class DeploymentServiceImpl implements DeploymentService, Remote, ClusterListener {
    private static final Logger LOG = LoggerFactory.getLogger(DeploymentServiceImpl.class);

    // Constants
    public static final String DEFAULT_DEPLOY_DIR = "${org.intalio.deploy.configDirectory}/../deploy";

    public static final String DEPLOY_COMPONENT = "DeploymentService";
    
    public static final String DEFAULT_DATASOURCE_JNDI_PATH = "java:/comp/env/jdbc/BPMSDB";
    
    //
    // Configuration
    //

    private int _scanPeriod = 5; // in seconds

    private String _deployDir = SystemPropertyUtils.resolvePlaceholders(DEFAULT_DEPLOY_DIR);

    private List<String> _requiredComponentManagers = new ArrayList<String>();
    
    private String _dataSourceJndiPath = DEFAULT_DATASOURCE_JNDI_PATH;

    //
    // Internal state
    //

    enum ServiceState {
        INITIALIZED, CLUSTERIZING, STARTING, STARTED, STOPPING
    }

    private ServiceState _serviceState;

    /**
     * Mapping of [componentType] to [ComponentManager name] e.g. "BPEL" => "ApacheOde"
     */
    private final Map<String, String> _componentTypes = 
        Collections.synchronizedMap(new HashMap<String, String>());

    /**
     * Mapping of [name] to [DeploymentManager] e.g. "MyApplication" => OdeComponentManager
     */
    private final Map<String, ComponentManager> _componentManagers = 
        Collections.synchronizedMap(new HashMap<String, ComponentManager>());

    private final Object LIFECYCLE_LOCK = new Object();

    private ReadWriteLock DEPLOY_LOCK = new ReentrantReadWriteLock();

    private DeploymentServiceCallbackImpl _callback = new DeploymentServiceCallbackImpl();

    //
    // Services
    //

    private Timer _timer;

    private final StartTask _startTask = new StartTask();

    private final TimerTask clusterizeTask = new TimerTask() {
        public void run() {
            try {
                cluster.start();
                onClustered();
            } catch( Exception e ) {
                e.printStackTrace();
            }
        }
    };

    private final ScanTask _scanTask = new ScanTask();

    private DataSource _dataSource;

    private Persistence _persist;
    
    private boolean replaceExistingAssemblies = false;

    // the Null-Object pattern
    private Cluster cluster = new SingleNodeCluster();

    // the Null-Object pattern
    private DeployMBeanServer _deployMBeanServer = new NullDeployMBeanServer();
    
    //
    // Constructor
    //

    public DeploymentServiceImpl() {
    }

    //
    // Accessors / Setters
    //

    private void writeLockDeploy() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Locking " + DEPLOY_LOCK);
        }
        DEPLOY_LOCK.writeLock().lock();
        for (ComponentManager c : _componentManagers.values()) {
            tryLock(c, true);
        }
    }

    private void writeUnlockDeploy() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Unlocking " + DEPLOY_LOCK);
        }
        for (ComponentManager c : _componentManagers.values()) {
            tryLock(c, false);
        }
        DEPLOY_LOCK.writeLock().unlock();
    }
    
    public DeployMBeanServer getDeployMBeanServer() {
    	return _deployMBeanServer;
    }
    
    public void setDeployMBeanServer(DeployMBeanServer deployMBeanServer) {
        _deployMBeanServer = deployMBeanServer;
    }
    
    public Cluster getCluster() {
        return cluster;
    }

    public void setCluster(Cluster cluster) {
        this.cluster = cluster;
    }

    public String getDeployDirectory() {
        return _deployDir;
    }

    public void setDeployDirectory(String path) {
        _deployDir = SystemPropertyUtils.resolvePlaceholders(path);
    }

    public int getScanPeriod() {
        return _scanPeriod;
    }

    public void setScanPeriod(int scanPeriod) {
        _scanPeriod = scanPeriod;
    }

    public void setReplaceExistingAssemblies(boolean replaceExistingAssemblies) {
        this.replaceExistingAssemblies = replaceExistingAssemblies;
    }

    public void addComponentTypeMapping(String componentType, String componentManager) {
        _componentTypes.put(componentType, componentManager);
    }

    public void removeComponentTypeMapping(String componentType) {
        _componentTypes.remove(componentType);
    }

    public List<String> getRequiredComponentManagers() {
        return _requiredComponentManagers;
    }

    public void setRequiredComponentManagers(List<String> componentManagers) {
        _requiredComponentManagers = componentManagers;
    }

    public void addRequiredComponentManager(String componentManager) {
        _requiredComponentManagers.add(componentManager);
    }

    public void removeRequiredComponentManager(String componentManager) {
        _requiredComponentManagers.remove(componentManager);
    }

    public DeploymentServiceCallback getCallback() {
        return _callback;
    }

    public DataSource getDataSource() {
        return _dataSource;
    }

    public void setDataSource(DataSource dataSource) {
        _dataSource = dataSource;
    }
    
    public void setDataSourceJndiPath(String dataSourceJndiPath) {
        _dataSourceJndiPath = dataSourceJndiPath;
    }
    //
    // Lifecycle Methods
    //

    /**
     * Initialize the service
     */
    public void init() {
        synchronized (LIFECYCLE_LOCK) {
            if (_serviceState != null) {
                throw new IllegalStateException("Service already initialized");
            }
            ensureDeploymentDirExists();
            if (_dataSource == null) {
                // by this time, if no datasource is set by the setter, use the default one
                try {
                    InitialContext initialContext = new InitialContext();
                    _dataSource = (DataSource)initialContext.lookup(_dataSourceJndiPath);
                } catch (NamingException e) {
                    throw new IllegalStateException("Couldn't find datasource through jndi");
                }
            }
            
            _persist = new Persistence(new File(_deployDir), _dataSource);
            _timer = new Timer("Deployment Service Timer", true);
            _serviceState = ServiceState.INITIALIZED;
            LOG.info(_("DeploymentService state is now INITIALIZED(replaceExistingAssemblies=" + replaceExistingAssemblies + ")."));
        }
    }

    /**
     * Start the service
     */
    public void start() {
        synchronized (LIFECYCLE_LOCK) {
            if (_serviceState != ServiceState.INITIALIZED) {
                throw new IllegalStateException("Service not initialized");
            }
            _serviceState = ServiceState.CLUSTERIZING;
            LOG.info(_("DeploymentService state is now CLUSTERIZING."));
            _timer.schedule(clusterizeTask, 0);
        }
    }

    public void onClustered() {
        synchronized (LIFECYCLE_LOCK) {
            if (_serviceState != ServiceState.CLUSTERIZING) {
                throw new IllegalStateException("Service not in clusterizing mode.");
            }
            _serviceState = ServiceState.STARTING;
            LOG.info(_("DeploymentService state is now STARTING"));
            checkRequiredComponentManagersAvailable();
        }
    }

    /**
     * Return true if service is started.
     */
    public boolean isStarted() {
        synchronized (LIFECYCLE_LOCK) {
            return _serviceState == ServiceState.STARTED;
        }
    }

    /**
     * Stop the service.
     * Acquires a lock over the timer task and cancels it.
     * Closes and disposes each deployment manager.
     */
    public void stop() {
        synchronized (LIFECYCLE_LOCK) {
            //acquire the assemblies first, before you wait to
            //acquire a lock over the timer task.
            //this way you have better chances to get the assemblies
            //before the connection to the DB closes.
            Collection<DeployedAssembly> assemblies = getDeployedAssemblies();
            if (_serviceState == ServiceState.STARTED) {
                _timer.cancel();
                cluster.shutdown();
            }
            _serviceState = ServiceState.STOPPING;
            LOG.info(_("DeploymentService state is now STOPPING"));

            
            stopAndDispose(assemblies);

            _serviceState = null;
            LOG.info(_("DeploymentService state is now STOPPED"));
        }
    }

    //
    // Operations
    //
    public DeploymentResult deployAssembly(String assemblyName, InputStream zip) {
        return deployAssembly(assemblyName, zip, true);
    }

    /**
     * Deploy a packaged (zipped) assembly
     */
    public DeploymentResult deployAssembly(String assemblyName, InputStream zip, boolean activate) {
        if( LOG.isDebugEnabled() ) LOG.debug("DEPLOYMENT.deployAssembly(" + assemblyName + ", replaceExistingAssemblies=" + replaceExistingAssemblies + ", activate=" + activate + ")");

        assertStarted();

        if (assemblyName.indexOf(".") >= 0) {
            Exception except = new Exception(_("Assembly name cannot contain dot character ('.'): {0}", assemblyName));
            LOG.error(except.getMessage());
            return convertToResult(except, newAssemblyId(assemblyName));
        }

        try {
            writeLockDeploy();
        	
            AssemblyId aid = versionAssemblyId(assemblyName);
            if (replaceExistingAssemblies) {
                Collection<DeployedAssembly> deployed = getDeployedAssemblies();
                for (DeployedAssembly da : deployed) {
                    if (da.getAssemblyId().getAssemblyName().equals(aid.getAssemblyName())) {
                        try {
                            stopAndDispose(da.getAssemblyId());
                            DeploymentResult result = undeployAssembly(da);
                            Utils.deleteRecursively(new File(da.getAssemblyDir()));
                            if (!result.isSuccessful()) {
                                return result;
                            }
                        } catch (Exception except) {
                            LOG.error(_("Error while undeploying assembly {0}", da.getAssemblyId()), except);
                            return convertToResult(except, aid);
                        }
                    }
                }
            }

            try {
                setMarkedAsInvalid(aid, _("Deploying {0} ...", aid));

                File assemblyDir = createAssemblyDir(aid);
                DeploymentResult result = null;
                try {
                    Utils.unzip(zip, assemblyDir);
                    result = deployExplodedAssembly(assemblyDir, activate);
                } finally {
                    if (result == null || !result.isSuccessful()) {
                        Utils.deleteRecursively(assemblyDir);
                    }
                }
                return result;
            } catch (Exception except) {
                throw new RuntimeException(except);
            } finally {
                clearMarkedAsInvalid(aid);
            }
        } finally {
            writeUnlockDeploy();
        }
    }

    /**
     * Deploy an exploded assembly
     */
    public DeploymentResult deployExplodedAssembly(File assemblyDir, boolean activate) {
        File parent = assemblyDir.getParentFile();
        while (true) {
            if (parent == null)
                break;
            parent = parent.getParentFile();
            if (_deployDir.equals(parent)) {
                throw new IllegalArgumentException(
                        "Assembly directory must either be direct child of deployment directory, or outside the deployment area: assemblDir="
                                + assemblyDir + " deploymentdir: " + _deployDir);
            }
        }

        AssemblyId aid;
        boolean local = assemblyDir.getParentFile().equals(new File(_deployDir));
        if (local) {
            aid = parseAssemblyId(assemblyDir.getName());
            if (isMarkedAsDeployed(aid)) {
                throw new RuntimeException("Assembly already deployed: " + aid);
            }
        } else {
            if (assemblyDir.getName().indexOf(".") >= 0) 
                throw new IllegalArgumentException("Assembly dir cannot contain dot '.' character: "+assemblyDir);
            aid = versionAssemblyId(assemblyDir.getName());
        }

        TemporaryResult results = new TemporaryResult(aid);
        try {
            writeLockDeploy();
            // mark as invalid while we deploy to avoid concurrency issues with scanner
            setMarkedAsInvalid(aid, _("Deploying {0} ...", aid));
    
            // if assemblyDir is outside deployDir, copy files into deployDir
            if (!local) {
                File d = getAssemblyDir(aid);
                try {
                    Utils.copyRecursively(assemblyDir, d);
                } catch (IOException except) {
                    Utils.deleteRecursively(d);
                    throw new RuntimeException(except);
                }
            }
    
            List<DeployedComponent> deployed = new ArrayList<DeployedComponent>();

            try {
                _persist.startTransaction();

                // Fix for BPMS-866 : Moved the below code down so that it will run only when it deploys successfully.
                
                /* The protocol has been changed after discussion with Alex.
                 * We now de-activate active versions when the the 'activate' is set to true.
                 * To have multiple active version for a new component manager,
                 * 1. deploy a version with the activate set to false
                 * 2. call active() on the new version
                 */
//                try { 
//                    if( activate ) {
//                        retire(aid);
//                    }
//                } catch(Exception e) {
//                    LOG.warn("Exeption during retiring old versions to activate the new one:", e);
//                }
                
                //*************************** Fix for BPMS-866 **************************************************

                try {
                    File[] files = assemblyDir.listFiles();

                    // deploy each component
                    for (File f : files) {
                        if (!f.isDirectory()) {
                            // ignore files at top-level
                            continue;
                        }
                        int dot = f.getName().lastIndexOf('.');
                        if (dot < 0) {
                            // ignore directories without extension (no mapping)
                            continue;
                        }
                        
                        LOG.debug("Deploying " + f.getCanonicalPath());
                        String componentType = f.getName().substring(dot+1);
                        String componentName = f.getName().substring(0, dot);
                        ComponentId component = new ComponentId(aid, componentName);

                        ComponentManager manager = getComponentManager(componentType);
                        LOG.debug("ComponentManager resolved: " + manager);
                        try {
                            ComponentManagerResult result = manager.deploy(component, f, activate);
                            results.addAll(component, componentType, result.getMessages());
                            // Sometimes, the component manager returns the same resources multiple times in the list
                            // let's take out the extra resource strings, yet keep the order
                            List<String> deployedResources = new ArrayList<String>();
                            for( String resource : result.getDeployedResources() ) {
                                if( !deployedResources.contains(resource) ) {
                                    deployedResources.add(resource);
                                } else {
                                    LOG.debug("Duplicate resource string found from the Component Manager deployment result, skipping: " + resource);
                                }
                            }
                            DeployedComponent deployedComponent = new DeployedComponent(component, f.getAbsolutePath(), componentType, deployedResources);
                            deployed.add(deployedComponent);
                        } catch (Exception except) {
                            String msg = _("Exception while deploying component {0}: {1}", componentName, except.getLocalizedMessage());
                            results.add(component, componentType, error(msg));
                            LOG.error(msg, except);
                        }
                    }
                } catch (Exception except) {
                    String msg = _("Exception while deploying assembly {0}: {1}", aid, except.getLocalizedMessage());
                    results.add(null, null, error(msg));
                    LOG.error(msg, except);
                }

                if (results.isSuccessful()) {
                	
                	/* The protocol has been changed after discussion with Alex.
                     * We now de-activate active versions when the the 'activate' is set to true.
                     * To have multiple active version for a new component manager,
                     * 1. deploy a version with the activate set to false
                     * 2. call active() on the new version
                     */
                    try { 
                        if( activate ) {
                            retire(aid);
                        }
                    } catch(Exception e) {
                        LOG.warn("Exeption during retiring old versions to activate the new one:", e);
                    }
                    
                    // update persistent state
                    DeployedAssembly assembly = loadAssemblyState(aid);
                    
                    _persist.retire(aid.getAssemblyName());
                    _persist.add(assembly, deployed);

                    _persist.commitTransaction();
                    
                    deployed(assembly, activate);
                    
                    cluster.sendMessage(new DeployedMessage(assembly, activate));
                    
                    initializeAndStart(aid);
                } else {
                    // in case of failure, we undeploy already deployed components
                    for (DeployedComponent dc : deployed) {
                        try {
                            ComponentManager manager = getComponentManager(dc.getComponentManagerName());                            
                            manager.undeploy(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(),false);
                        } catch (Exception except) {
                            String msg = _("Exception while undeploying component {0} after failed deployment: {1}", dc.getComponentId(),
                                    except.getLocalizedMessage());
                            results.add(dc, error(msg));
                            LOG.error(msg, except);
                        }
                    }
                    _persist.rollbackTransaction("Deployment errors");
                }

                setMarkedAsDeployed(aid, results.isSuccessful());
                clearMarkedAsInvalid(aid);
            } catch (Throwable e) {
                String msg = _("Unexpected exception {0}: {1}", e.getClass(), e.getMessage());
                results.add(null, null, error(msg));
                _persist.rollbackTransaction(msg);
            }
        } finally {
            writeUnlockDeploy();
        }
        
        return results.finalResult();
    }

    /**
     * Undeploy an assembly by name
     */
    public DeploymentResult undeployAssembly(AssemblyId aid) {
        if( LOG.isDebugEnabled() ) LOG.debug("DEPLOYMENT.undeployAssembly(" + aid + ")");
        
        assertStarted();
        
        if (!exist(aid))
            return errorResult(aid, "Assembly directory does not exist: {0}", aid);
        
        DeployedAssembly assembly = loadAssemblyState(aid);
        DeployedAssembly assemblyFromDatabase = _persist.load().get(aid);
        if( assemblyFromDatabase != null ) {
            assembly = assemblyFromDatabase;
        }
        
        stopAndDispose(aid);
        if (cluster.isCoordinator()) {
            cluster.sendMessage(new UndeployedMessage(assembly));
        }
        onUndeployed(assembly);
        
        try {
            writeLockDeploy();
            try {
            	
                return undeployAssembly(assembly);
            } finally {
                try {
                    Utils.deleteRecursively(new File(assembly.getAssemblyDir()));
                } catch (Exception e) {
                    LOG.warn(_("Exception while undeploying assembly {0}: {1}", assembly.getAssemblyId(), e.toString()));
                }
            }
        } finally {
        	writeUnlockDeploy();
        }
    }

    /**
     * Scan all exploded assemblies in the deployment directory, and optionally
     * start newly deployed assemblies.
     */
    public void scan() {
        if( !cluster.isCoordinator() ) {
            return;
        }
        
        
        
        LOG.debug(_("Scanning deployment directory {0}", _deployDir));
        LOG.debug(_("Component managers: {0}", _componentManagers));
        try {
        	writeLockDeploy();
            Map<AssemblyId, DeployedAssembly> deployedMap = _persist.load();
            LOG.debug(_("Deployed assemblies: {0}", deployedMap.keySet()));

            Set<AssemblyId> available = new HashSet<AssemblyId>();
            Set<AssemblyId> availableWithDeployMark = new HashSet<AssemblyId>();
            // read available assemblies
            {
	            File deployDir = new File(_deployDir);
                File[] files = deployDir.listFiles();
                if (!deployDir.exists() || files == null) {
                    LOG.error("!!! FATAL ERROR !!! (Please check following error properly or Call Intalio Support for further assistance)  Deployment directory does not exists or it is not a directory: " + _deployDir);
                    return;
                }
                
                for (int i = 0; i < files.length; ++i) {
                    if (files[i].isDirectory()) {
                        AssemblyId aid = parseAssemblyId(files[i].getName());
                        available.add(aid);
                    } else {
                        String name = files[i].getName();
                        if (name.endsWith(".deployed")) {
                            availableWithDeployMark.add(parseAssemblyId(
                                    name.substring(0, name.length() - ".deployed".length())));
                        }
                    }
                    
                }
                LOG.debug(_("Available assemblies on file system: {0}", available));
            }

            // Phase 1: undeploy missing assemblies
            Set<DeployedAssembly> undeploy = new HashSet<DeployedAssembly>();
            // check for previously deployed but now missing
            for (DeployedAssembly assembly : deployedMap.values()) {
                // if helloWorld is removed but helloWorld.deployed is there,
                // then undeploy.
                // if you cannot find both it is possible the file system is in flux
                // and you should not undeploy.
                if (!available.contains(assembly.getAssemblyId()) && 
                        availableWithDeployMark.contains(assembly.getAssemblyId()))
                    undeploy.add(assembly);
            }

            // check for available but deployed flag missing
            for (AssemblyId aid : available) {
                DeployedAssembly assembly = deployedMap.get(aid);
                if (assembly != null && !isMarkedAsDeployed(aid))
                    undeploy.add(assembly);
            }

            // stop and dispose all at once
            stopAndDispose(undeploy);

            for (DeployedAssembly assembly : undeploy) {
                DeployedAssembly assemblyFromDatabase = deployedMap.get(assembly.getAssemblyId());
                if( assemblyFromDatabase != null ) {
                    assembly = assemblyFromDatabase;
                }
                
                if (cluster.isCoordinator()) {
                    cluster.sendMessage(new UndeployedMessage(assembly));
                }

                DeploymentResult result = undeployAssembly(assembly);
                if (result.isSuccessful())
                    LOG.info(_("Undeployed assembly: {0}", assembly.getAssemblyId()));
                else
                    LOG.error(_("Error while undeploying assembly {0}: {1}", assembly.getAssemblyId()), result);
                deployedMap.remove(assembly.getAssemblyId());
            }

            // phase 2: deploy new assemblies
            File[] files = new File(_deployDir).listFiles();
            for (int i = 0; i < files.length; ++i) {
                if (files[i].isDirectory()) {
                    AssemblyId aid = parseAssemblyId(files[i].getName());
                    if (!isMarkedAsDeployed(aid) && !isMarkedAsInvalid(aid)) {
                        try {
                            // auto-detected assemblies are always activated after deployment
                            DeploymentResult result = deployExplodedAssembly(files[i], true);
                            if (result.isSuccessful()) {
                                LOG.info(_("Deployed Assembly: {0}", result));
                                clearMarkedAsInvalid(aid);
                            } else {
                                LOG.warn(_("Assembly deployment failed: {0}", result));
                                setMarkedAsInvalid(aid, result.toString());
                            }
                        } catch (Exception except) {
                            LOG.error(_("Error deploying assembly {0}. Assembly will be marked as invalid.", files[i]), except);
                            setMarkedAsInvalid(aid, except.toString());
                        }
                    } else if(isMarkedAsDeployed(aid) && !deployedMap.containsKey(aid)) {
                        if(LOG.isWarnEnabled()) LOG.warn(_("Inconsistent states found between the file system and the deployment service database!!"));
                        if(LOG.isWarnEnabled()) LOG.warn(_("A valid assembly for " + aid + " exists on the file system but missing in the database; You can re-deploy the assembly by removing the .deployed files for the assemblies."));
                    }
                }
            }
        } finally {
        	writeUnlockDeploy();
        }
    }

    /**
     * Obtain the current list of deployed assemblies
     */
    public Collection<DeployedAssembly> getDeployedAssemblies() {
        try {
        	writeLockDeploy();
            Map<AssemblyId, DeployedAssembly> assemblies = _persist.load();
            return assemblies.values();
        } finally {
        	writeUnlockDeploy();
        }
    }
    
    /**
     * Obtain the latest version of a given assembly
     */
    public DeploymentResult getAssemblyVersion(String assemblyName) {
        
        
    	int assemblyVersion= _persist.getAssemblyVersion(assemblyName);
    	TemporaryResult result = new TemporaryResult(new AssemblyId(assemblyName,assemblyVersion));
    	
        return result.finalResult(); 
        
    }


    public Collection<DeployedAssembly> readDeployedAssemblies() {
        List<DeployedAssembly> assemblies = new ArrayList<DeployedAssembly>();
        try {
        	writeLockDeploy();
            File[] files = new File(_deployDir).listFiles();
            for (int i = 0; i < files.length; ++i) {
                if (files[i].isDirectory()) {
                    AssemblyId aid = parseAssemblyId(files[i].getName());
                    if (isMarkedAsDeployed(aid) && !isMarkedAsInvalid(aid)) {
                        try {
                            assemblies.add(loadAssemblyState(aid));
                        } catch (Exception except) {
                            LOG.error(_("Error reading assembly state {0}", aid), except);
                        }
                    }
                }
            }
        } finally {
        	writeUnlockDeploy();
        }
        return assemblies;
    }

    //
    // Private / Protected Internal Methods
    //

    /**
     * Undeploy an assembly
     */
    private DeploymentResult undeployAssembly(DeployedAssembly assembly) {
        AssemblyId aid = assembly.getAssemblyId();

        TemporaryResult result = new TemporaryResult(aid);
        try {
        	writeLockDeploy();
            try {
                // undeploy all components
            	
	                for (DeployedComponent dc : assembly.getDeployedComponents()) {
	                    try {
	                        ComponentManager manager = getComponentManager(dc.getComponentManagerName());
	                        
	                        manager.undeploy(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(),assembly.isActive());
	                        
	                        _deployMBeanServer.unregisterComponent(dc.getComponentManagerName(), dc.getComponentId());
	                    } catch (Exception except) {
	                        String msg = _("Exception while undeploying component {0}: {1}", dc.getComponentId(), except.getLocalizedMessage());
	                        result.add(dc, error(msg));
	                        LOG.error(msg, except);
	                    }
	            
            	}
            } catch (Exception except) {
                String msg = _("Exception while undeploying assembly {0}: {1} ", aid, except.getLocalizedMessage());
                result.add(null, null, error(msg));
                LOG.error(msg, except);
            } finally {
                // update persistent state
                _persist.remove(aid);

                if (!result.isSuccessful() && exist(aid)) {
                    setMarkedAsInvalid(aid, result.toString());
                }
                setMarkedAsDeployed(aid, false);
                
                _deployMBeanServer.unregisterAssembly(aid);
            }
        } finally {
        	writeUnlockDeploy();
        }
        return result.finalResult();
    }

    public void onDeployed(DeployedAssembly assembly, boolean activate) {
        deployed(assembly, activate);

        initializeAndStart(assembly.getAssemblyId());
    }
    
    private void deployed(DeployedAssembly assembly, boolean activate) {
    	_deployMBeanServer.registerAssembly(assembly);
        
        for (DeployedComponent dc : assembly.getDeployedComponents()) {
            try {
                if(LOG.isDebugEnabled()) LOG.debug(_("Deployed component {0}", dc));
                ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                manager.deployed(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(), activate);
                
                _deployMBeanServer.registerComponent(assembly, dc);
            } catch (Exception except) {
                String msg = _("Error during deployment notification of component {0}: {1}", dc.getComponentId(), except);
                if(LOG.isErrorEnabled()) LOG.error(msg, except);
                break;
            }
        }
    }

    public void onUndeployed(DeployedAssembly assembly) {
        for (DeployedComponent dc : assembly.getDeployedComponents()) {
            try {
                if(LOG.isDebugEnabled()) LOG.debug(_("Undeployed component {0}", dc));
                ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                manager.undeployed(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources());
            } catch (Exception except) {
                String msg = _("Error during undeployment notification of component {0}: {1}", dc.getComponentId(), except);
                if(LOG.isErrorEnabled()) LOG.error(msg, except);
                break;
            }
        }
    }
    
    public void onActivated(DeployedAssembly assembly) {
        for (DeployedComponent dc : assembly.getDeployedComponents()) {
            try {
                LOG.debug(_("Activated component {0}", dc));
                ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                manager.activated(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources());
            } catch (Exception except) {
                String msg = _("Error during activation notification of component {0}: {1}", dc.getComponentId(), except);
                LOG.error(msg, except);
                break;
            }
        }
    }
    
    public void onRetired(DeployedAssembly assembly) {
        for (DeployedComponent dc : assembly.getDeployedComponents()) {
            try {
                LOG.debug(_("Retired component {0}", dc));
                ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                manager.retired(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources());
            } catch (Exception except) {
                String msg = _("Error during retirement notification of component {0}: {1}", dc.getComponentId(), except);
                LOG.error(msg, except);
                break;
            }
        }
    }

    private void tryLock(ComponentManager manager, boolean lock) {
    	if (manager instanceof ComponentManagerLockAware) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Component Manager " + manager + " is Lock Aware");
            }
            if (lock) {
                ((ComponentManagerLockAware) manager).deploymentLock();
            } else {
                ((ComponentManagerLockAware) manager).deploymentUnlock();
            }
    	} else {
    		if (LOG.isDebugEnabled()) {
    			LOG.debug("Component Manager " + manager + " is not Lock Aware, deployment withing engine is not atomic");
    		}
    	}
    }
    
    private DeployedAssembly initializeAndStart(AssemblyId aid) {
        DeployedAssembly assembly = loadAssemblyState(aid);
        // patch with the one from DB
        DeployedAssembly assemblyFromDatabase = _persist.load().get(aid);
        if( assemblyFromDatabase != null ) {
            assembly = assemblyFromDatabase;
        }
        List<DeployedAssembly> assemblies = new ArrayList<DeployedAssembly>();
        assemblies.add(assembly);
        initializeAndStart(assemblies);
        
        return assembly;
    }

    private boolean initializeAndStart(Collection<DeployedAssembly> assemblies) {
        boolean success = true;

        // Phase 1: Initialize all components of all assemblies
        Map<DeployedComponent, DeployedAssembly> initialized = new HashMap<DeployedComponent, DeployedAssembly>();
        for (DeployedAssembly assembly : assemblies) {
            for (DeployedComponent dc : assembly.getDeployedComponents()) {
                try {
                    LOG.debug(_("Initialize component {0}", dc));
                    ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                    manager.initialize(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(), assembly.isActive());
                    initialized.put(dc, assembly);
                } catch (Exception except) {
                    success = false;
                    String msg = _("Error during initialization of component {0}: {1}", dc.getComponentId(), except);
                    LOG.error(msg, except);
                    break;
                }
            }
        }

        if (success) {
            // Phase 2: Startup all components
            for (DeployedAssembly assembly : assemblies) {
                for (DeployedComponent dc : assembly.getDeployedComponents()) {
                    try {
                        LOG.debug(_("Start component {0}", dc));
                        ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                        manager.start(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(), assembly.isActive());
                    } catch (Exception except) {
                        String msg = _("Error during startup of component {0}: {1}", dc.getComponentId(), except);
                        LOG.error(msg, except);
                    }
                }
            }
        } else {
            for (DeployedComponent dc : initialized.keySet()) {
                try {
                    ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                    manager.dispose(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(), initialized.get(dc).isActive());
                } catch (Exception except) {
                    String msg = _("Error during disposition of component {0} after startup failure: {1}", dc.getComponentId(), except);
                    LOG.error(msg, except);
                }
            }
        }
        
        return success;
    }

    private void stopAndDispose(AssemblyId aid) {
        DeployedAssembly assembly = loadAssemblyState(aid);
        List<DeployedAssembly> assemblies = new ArrayList<DeployedAssembly>();
        assemblies.add(assembly);
        stopAndDispose(assemblies);
    }

    private void stopAndDispose(Collection<DeployedAssembly> assemblies) {
        // Phase 1: Stop all components
        for (DeployedAssembly assembly : assemblies) {
            for (DeployedComponent dc : assembly.getDeployedComponents()) {
                ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                try {
                    LOG.debug(_("Stop component {0}", dc));
                    manager.stop(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(), assembly.isActive());
                } catch (Exception except) {
                    String msg = _("Error while stopping component {0}: {1}", dc.getComponentId(), except);
                    LOG.error(msg, except);
                }
            }
        }

        // Phase 2: Dispose all components
        for (DeployedAssembly assembly : assemblies) {
            for (DeployedComponent dc : assembly.getDeployedComponents()) {
                ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                try {
                    LOG.debug(_("Dispose component {0}", dc));
                    manager.dispose(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(), assembly.isActive());
                } catch (Exception except) {
                    String msg = _("Error while disposing component {0}: {1}", dc.getComponentId(), except);
                    LOG.error(msg, except);
                }
            }
        }
    }

    public DeploymentResult activate(AssemblyId assemblyId) {
        TemporaryResult results = new TemporaryResult(assemblyId);
        
        DeployedAssembly assembly = loadAssemblyState(assemblyId);
        DeployedAssembly assemblyFromDatabase = _persist.load().get(assemblyId);
        if( assemblyFromDatabase != null ) {
            assembly = assemblyFromDatabase;
        }
        for (DeployedComponent dc : assembly.getDeployedComponents()) {
            ComponentManager manager = getComponentManager(dc.getComponentManagerName());
            try {
                LOG.debug(_("Activate component {0}", dc));
                manager.activate(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources());
            } catch (Exception except) {
                String msg = _("Error while activating component {0}: {1}", dc.getComponentId(), except);
                results.add(dc.getComponentId(), dc.getComponentManagerName(), new DeploymentMessage(Level.ERROR, msg));
                LOG.error(msg, except);
            }
        }
        _persist.activate(assemblyId.getAssemblyName(), assemblyId.getAssemblyVersion());
        cluster.sendMessage(new ActivatedMessage(assembly));
        
        return results.finalResult(); 
    }
    
    /**
     * Activates the process of the assembly specified by the given assembly id. 
     *
     */    
    public DeploymentResult activateProcess(AssemblyId assemblyId, String processName) {
     TemporaryResult results = new TemporaryResult(assemblyId);
        
        DeployedAssembly assembly = loadAssemblyState(assemblyId);
        DeployedAssembly assemblyFromDatabase = _persist.load().get(assemblyId);
        if( assemblyFromDatabase != null ) {
            assembly = assemblyFromDatabase;
        }
        String pipaFormUrl=_persist.fetchPIPAUrl(assemblyId, processName);
        for (DeployedComponent dc : assembly.getDeployedComponents()) {
            ComponentManager manager = getComponentManager(dc.getComponentManagerName());
            try {
                LOG.debug(_("Activate component {0}", dc));
                manager.activateProcess(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(),pipaFormUrl);
            } catch (Exception except) {
                String msg = _("Error while activating component {0}: {1}", dc.getComponentId(), except);
                results.add(dc.getComponentId(), dc.getComponentManagerName(), new DeploymentMessage(Level.ERROR, msg));
                LOG.error(msg, except);
            }
        }
        _persist.activate(assemblyId.getAssemblyName(), assemblyId.getAssemblyVersion());
        cluster.sendMessage(new ActivatedMessage(assembly));
        
        return results.finalResult(); 	
    	
    	
    }

    /**
     * Retires all revisions for the assembly. The version number inside the
     * AssemblyId is ignored.
     */
    public DeploymentResult retire(AssemblyId assemblyId) {
        TemporaryResult results = new TemporaryResult(assemblyId);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Retiring components matching " + assemblyId.getAssemblyName());
        }
        Collection<DeployedAssembly> assembliesToRetire = new ArrayList<DeployedAssembly>();
        Map<AssemblyId, DeployedAssembly> assembliesById = _persist.load();
        for( AssemblyId id : assembliesById.keySet() ) {
            if( id.getAssemblyName().equals(assemblyId.getAssemblyName()) 
                    && assembliesById.get(id).isActive() ) {
                DeployedAssembly assembly = loadAssemblyState(id);
                // Fix for WF-1450 
                DeployedAssembly assemblyFromDatabase = _persist.load().get(id);
                if( assemblyFromDatabase != null ) {
                    assembly = assemblyFromDatabase;
                }
                // End WF-1450
                assembliesToRetire.add(assembly);
                for (DeployedComponent dc : assembly.getDeployedComponents()) {
                    ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                    try {
                        LOG.debug(_("Retire component {0}", dc));
                        manager.retire(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources());
                    } catch (Exception except) {
                        String msg = _("Error while retiring component {0}: {1}", dc.getComponentId(), except);
                        results.add(dc.getComponentId(), dc.getComponentManagerName(), new DeploymentMessage(Level.ERROR, msg));
                        LOG.error(msg, except);
                    }
                }
            }
        }
        _persist.retire(assemblyId.getAssemblyName());
        for( DeployedAssembly assembly : assembliesToRetire ) {
            cluster.sendMessage(new RetiredMessage(assembly));
        }

        return results.finalResult(); 
    }
    
    /**
     * Retires the version of the assembly specified by the given assembly id. This method does nothing if the 
     * given version is already retired.
     *
     */    
    public DeploymentResult retireAssembly(AssemblyId assemblyId) {
        TemporaryResult results = new TemporaryResult(assemblyId);
        if (LOG.isDebugEnabled()) {
            LOG.debug("Retiring Assembly : name=" + assemblyId.getAssemblyName() +" version="+ assemblyId.getAssemblyVersion());
        }
        Collection<DeployedAssembly> assembliesToRetire = new ArrayList<DeployedAssembly>();
        DeployedAssembly assembly = loadAssemblyState(assemblyId);                
        DeployedAssembly assemblyFromDatabase = _persist.load().get(assemblyId);
        if( assemblyFromDatabase != null ) {
            assembly = assemblyFromDatabase;
        }
        if(assembly.isActive()){
        	assembliesToRetire.add(assembly);
            for (DeployedComponent dc : assembly.getDeployedComponents()) {
                ComponentManager manager = getComponentManager(dc.getComponentManagerName());
                try {
                    LOG.debug(_("Retire component {0}", dc));
                    manager.retire(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources());
                } catch (Exception except) {
                    String msg = _("Error while retiring component {0}: {1}", dc.getComponentId(), except);
                    results.add(dc.getComponentId(), dc.getComponentManagerName(), new DeploymentMessage(Level.ERROR, msg));
                    LOG.error(msg, except);
                }
            }
        }

        _persist.retireAssembly(assemblyId);
        for( DeployedAssembly depployedAssembly : assembliesToRetire ) {
            cluster.sendMessage(new RetiredMessage(depployedAssembly));
        }

        return results.finalResult(); 
    }
    
    /**
     * Retires the process of the assembly specified by the given assembly id. 
     *
     */    
    public DeploymentResult retireProcess(AssemblyId assemblyId, String processName) {
    	  TemporaryResult results = new TemporaryResult(assemblyId);
          
          DeployedAssembly assembly = loadAssemblyState(assemblyId);
          DeployedAssembly assemblyFromDatabase = _persist.load().get(assemblyId);
          if( assemblyFromDatabase != null ) {
              assembly = assemblyFromDatabase;
          }
          String pipaFormUrl=_persist.fetchPIPAUrl(assemblyId, processName);
          for (DeployedComponent dc : assembly.getDeployedComponents()) {
              ComponentManager manager = getComponentManager(dc.getComponentManagerName());
              try {
                  LOG.debug(_("Retire component {0}", dc));
                  manager.retireProcess(dc.getComponentId(), new File(dc.getComponentDir()), dc.getDeployedResources(),pipaFormUrl);
              } catch (Exception except) {
                  String msg = _("Error while retiring component {0}: {1}", dc.getComponentId(), except);
                  results.add(dc.getComponentId(), dc.getComponentManagerName(), new DeploymentMessage(Level.ERROR, msg));
                  LOG.error(msg, except);
              }
          }   
          cluster.sendMessage(new RetiredMessage(assembly));
          return results.finalResult(); 	
    }

    private void checkRequiredComponentManagersAvailable() {
        boolean available = true;
        StringBuffer missing = new StringBuffer();
        for (String cm : _requiredComponentManagers) {
            if (!_componentManagers.containsKey(cm)) {
                if (missing.length() > 0)
                    missing.append(", ");
                missing.append(cm);
                available = false;
            }
        }
        synchronized (LIFECYCLE_LOCK) {
            if (ServiceState.STARTING.equals(_serviceState) && available) {
                synchronized (_startTask) {
                    if (!_startTask.scheduled) {
                        _startTask.scheduled = true;
                        _timer.schedule(_startTask, 0);
                    }
                }
            }
        }

        if (!available)
            LOG.info(_("Waiting for component managers: {0}", missing));
    }

    public void warmUpCluster() throws Exception {
        cluster.warmUp();
    }
    
    private void internalStart() {
        try {
        	writeLockDeploy();
            try {
                scan();
            } catch (Exception e) {
                LOG.error(_("Error while scanning deployment repository"), e);
            }

            Collection<DeployedAssembly> assemblies = getDeployedAssemblies();
            // let the runtime ComponentManagers be aware of the deployed components
            for( DeployedAssembly assembly : assemblies ) {
                for( DeployedComponent component : assembly.getDeployedComponents() ) {
                    ComponentManager manager = getComponentManager(component.getComponentManagerName());
                    manager.deployed(component.getComponentId(), new File(component.getComponentDir()), component.getDeployedResources(), assembly.isActive());

                    _deployMBeanServer.registerComponent(assembly, component);
                }
                _deployMBeanServer.registerAssembly(assembly);
            }
            
            if (initializeAndStart(assemblies)) {
                _serviceState = ServiceState.STARTED;
                LOG.info(_("DeploymentService state is now STARTED"));

                _timer.schedule(_scanTask, _scanPeriod * 1000, _scanPeriod * 1000);
            }
        } finally {
        	writeUnlockDeploy();
        }
    }


    /**
     * Ensure deployment directory exists
     */
    private void ensureDeploymentDirExists() {
        if (_deployDir.contains("${"))
            throw new IllegalStateException("Invalid deployment directory: " + _deployDir);
        File dir = new File(_deployDir);
        if (!dir.exists() || !dir.isDirectory()) {
            LOG.error("!!! FATAL ERROR !!! (Please check following error properly or Call Intalio Support for further assistance)  Deployment directory does not exists or it is not a directory: " + _deployDir);
        }
    }

    /**
     * Create an assembly directory
     */
    private File createAssemblyDir(AssemblyId aid) {
        File dir = getAssemblyDir(aid);
        if (dir.exists()) {
            throw new IllegalStateException("Deployment path already exists: " + dir);
        }
        LOG.debug("Creating deployment directory: " + _deployDir);
        boolean created = dir.mkdirs();
        if (!created) {
            throw new IllegalStateException("Unable to create deployment directory: " + _deployDir);
        }
        return dir;
    }

    /**
     * Create a unique assembly id, e.g. myAssembly.1, myAssembly.2, ...
     */
    private AssemblyId versionAssemblyId(String assemblyName) {
        int version = AssemblyId.NO_VERSION;
        if (new File(_deployDir, assemblyName).exists())
            version = 2;
        File[] files = Utils.listFiles(new File(_deployDir), assemblyName+".*");
        for (File f: files) {
            String name = f.getName();
            int pos = name.lastIndexOf(".");
            try {
                int v = Integer.parseInt(name.substring(pos+1));
                if (version == AssemblyId.NO_VERSION || v >= version) 
                    version = v+1;
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return new AssemblyId(assemblyName, version);
    }

    DeployedAssembly loadAssemblyState(AssemblyId aid) {
        File assemblyDir = getAssemblyDir(aid);
        if (!assemblyDir.exists()) {
            throw new IllegalStateException("Assembly does not exist: " + aid);
        }
        if (!assemblyDir.isDirectory()) {
            throw new IllegalArgumentException("Assembly name does not map to a directory: " + assemblyDir);
        }

        List<DeployedComponent> components = new ArrayList<DeployedComponent>();

        File[] files = assemblyDir.listFiles();

        for (File componentDir : files) {
            if (!componentDir.isDirectory()) {
                // ignore files at top-level
                continue;
            }
            int dot = componentDir.getName().lastIndexOf('.');
            if (dot < 0) {
                // ignore directories without extension (no mapping)
                continue;
            }
            String componentType = componentDir.getName().substring(dot+1);
            String componentName = componentDir.getName().substring(0, dot);
            ComponentId component = new ComponentId(aid, componentName);
            components.add(new DeployedComponent(component, componentDir.getAbsolutePath(), componentType));
        }
        
        return new DeployedAssembly(aid, assemblyDir.getAbsolutePath(), components, false);
    }

    private ComponentManager getComponentManager(String componentType) {
        ComponentManager manager = _componentManagers.get(componentType);
        if (manager == null) {
            String componentManagerName = _componentTypes.get(componentType);
            if (componentManagerName != null) {
                manager = _componentManagers.get(componentManagerName);
            }
        }
        if (manager == null)
            manager = new MissingComponentManager(componentType);
        
        return manager;
    }

    private File getAssemblyDir(AssemblyId aid) {
        return new File(_deployDir, toDirName(aid));
    }

    private boolean exist(AssemblyId aid) {
        return getAssemblyDir(aid).exists();
    }

    private File getDeployedFile(AssemblyId aid) {
        return new File(_deployDir, toDirName(aid) + ".deployed");
    }

    private File getInvalidFile(AssemblyId aid) {
        return new File(_deployDir, toDirName(aid) + ".invalid");
    }

    private String toDirName(AssemblyId aid) {
        if (aid.getAssemblyVersion() == AssemblyId.NO_VERSION)
            return aid.getAssemblyName();
        else
            return aid.getAssemblyName() + "." + aid.getAssemblyVersion();
    }

    private boolean isMarkedAsDeployed(AssemblyId aid) {
        return getDeployedFile(aid).exists();
    }

    private void setMarkedAsDeployed(AssemblyId aid, boolean isDeployed) {
        File deployed = getDeployedFile(aid);
        if (isDeployed)
            Utils.createFile(deployed);
        else
            Utils.deleteFile(deployed);
    }

    private boolean isMarkedAsInvalid(AssemblyId aid) {
        return getInvalidFile(aid).exists();
    }

    private void clearMarkedAsInvalid(AssemblyId aid) {
        File invalid = getInvalidFile(aid);
        Utils.deleteFile(invalid);
    }

    private void setMarkedAsInvalid(AssemblyId aid, String message) {
        File invalid = getInvalidFile(aid);
        Utils.createFile(invalid);
        FileWriter writer = null;
        BufferedWriter out = null;
        try {
            writer = new FileWriter(invalid);
            out = new BufferedWriter(writer);
            out.write(message);
            out.close();
        } catch (Exception e) {
            LOG.error(_("Error while writing to {0}", invalid), e);
        } finally {
            try {
              if (writer != null) writer.close();
            } catch (Exception e) { /* ignore */ }
        }
    }

    private DeploymentResult convertToResult(Exception except, AssemblyId aid) {
        DeploymentMessage msg = new DeploymentMessage(Level.ERROR, except.getLocalizedMessage());
        return new DeploymentResult(aid, false, msg);
    }

    static DeploymentMessage error(String message) {
        return new DeploymentMessage(Level.ERROR, message);
    }

    private DeploymentMessage error(String pattern, Object... arguments) {
        return new DeploymentMessage(Level.ERROR, _(pattern, arguments));
    }

    private DeploymentResult errorResult(AssemblyId aid, String pattern, Object... arguments) {
        DeploymentMessage msg = error(pattern, arguments);
        DeploymentResult result = new DeploymentResult(aid, false, msg);
        return result;
    }

    private AssemblyId parseAssemblyId(String dirName) {
        String assemblyName = dirName;
        int version = AssemblyId.NO_VERSION;
        int pos = dirName.length();
        while (pos > 1) {
            pos--;
            char c = dirName.charAt(pos);
            if (Character.isDigit(c))
                continue;
            if (c != '.')
                break;
            version = Integer.parseInt(dirName.substring(pos + 1));
            assemblyName = dirName.substring(0, pos);
        }
        return new AssemblyId(assemblyName, version);
    }

    private AssemblyId newAssemblyId(String assemblyName) {
        return new AssemblyId(assemblyName, AssemblyId.NO_VERSION);
    }

    private void assertStarted() {
        synchronized (LIFECYCLE_LOCK) {
            int secs = 10;
            while (secs-- > 0 && _serviceState != ServiceState.STARTED) {
                try {
                    LOG.info("Deployment has been requested. However, the service is still starting up(retrying in 1 sec).");
                    LIFECYCLE_LOCK.wait(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            
            if (_serviceState == ServiceState.CLUSTERIZING) {
                throw new IllegalStateException(_("Not enough number of nodes are discovered in the cluster. Deployment service will be enabled when enough nodes are available."));
            } else if (_serviceState != ServiceState.STARTED) {
                throw new IllegalStateException(_("Service not started.  Current state is {0}", _serviceState));
            }
        }
    }

    /**
     * Recurring scan of the deployment directory every "scanPeriod"
     * milliseconds
     */
    class StartTask extends TimerTask {
        boolean scheduled = false;
        
        public void run() {
            internalStart();
            synchronized (this) {
                scheduled = false;
            }
        }
    }

    /**
     * Recurring scan of the deployment directory every "scanPeriod"
     * milliseconds
     */
    class ScanTask extends TimerTask {
        public void run() {
            synchronized (LIFECYCLE_LOCK) {
                if (!ServiceState.STARTED.equals(_serviceState)) {
                    _timer.cancel();
                    return;
                }
            }
            try {
                scan();
            } catch (Exception e) {
                LOG.error("Error while scanning deployment repository", e);
            }
        }
    }

    /**
     * Callback when ComponentManager's become available/unavailable.
     * <p>
     * Note:  This implementation class needs to be public due to Java reflection limitations
     *        http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=4071957
     */
    public class DeploymentServiceCallbackImpl implements DeploymentServiceCallback {
        public void available(ComponentManager manager) {
            String name = manager.getComponentManagerName();
            // proxy the manager for remote class loaders
            RemoteProxy<ComponentManager> proxy = new RemoteProxy<ComponentManager>(manager, getClass().getClassLoader(), manager.getClass().getClassLoader());
            _componentManagers.put(name, proxy.newProxyInstance());
            LOG.info(_("ComponentManager now available: {0}", name));
            
            synchronized (LIFECYCLE_LOCK) {
                if (ServiceState.STARTED.equals(_serviceState)) {
                    try {
                    	writeLockDeploy();
                    	
                        Collection<DeployedAssembly> assemblies = getDeployedAssemblies();
                        for (DeployedAssembly assembly : assemblies) {
                            Collection<DeployedComponent> components = assembly.getDeployedComponents();
                            for (DeployedComponent component: components) {
                                String type = _componentTypes.get(component.getComponentManagerName());
                                if (name.equals(component.getComponentManagerName()) || name.equals(type)) {
                                    try {
                                        LOG.debug(_("Initialize component {0}", component));
                                        manager.initialize(component.getComponentId(), new File(component.getComponentDir()), component.getDeployedResources(), assembly.isActive());
                                    } catch (Exception except) {
                                        LOG.error(_("Error while activating component {0}", component), except);
                                    }
                                    try {
                                        LOG.debug(_("Start component {0}", component));
                                        manager.start(component.getComponentId(), new File(component.getComponentDir()), component.getDeployedResources(), assembly.isActive());
                                    } catch (Exception except) {
                                        LOG.error(_("Error while activating component {0}", component), except);
                                    }
                                }
                            }
                        }
                    } finally {
                    	writeUnlockDeploy();
                    }
                } else {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("State is " + _serviceState);
                    }
                }
            }
            checkRequiredComponentManagersAvailable();
        }

        public void unavailable(ComponentManager manager) {
            _componentManagers.remove(manager.getComponentManagerName());
        }

    }

    /**
     * Accumulate results during deployment operations
     */
    class TemporaryResult {
        private AssemblyId _aid;
        boolean _success = true;
        List<DeploymentMessage> _messages = new ArrayList<DeploymentMessage>();

        TemporaryResult(AssemblyId aid) {
            _aid = aid;
        }

        boolean add(DeployedComponent dc, DeploymentMessage msg) {
            return add(dc.getComponentId(), dc.getComponentManagerName(), msg);
        }

        boolean add(ComponentId componentId, String componentManagerName, DeploymentMessage msg) {
            _messages.add(msg);
            msg.setComponentId(componentId);
            msg.setComponentManagerName(componentManagerName);
            if (msg.isError())
                _success = false;
            return msg.isError();
        }

        boolean addAll(DeployedComponent dc, List<DeploymentMessage> messages) {
            return addAll(dc.getComponentId(), dc.getComponentManagerName(), messages);
        }

        boolean addAll(ComponentId ComponentId, String componentManagerName, List<DeploymentMessage> messages) {
            boolean localSuccess = true;
            _messages.addAll(messages);
            for (DeploymentMessage m : messages) {
                m.setComponentId(ComponentId);
                m.setComponentManagerName(componentManagerName);
                if (m.isError()) {
                    _success = false;
                    localSuccess = false;
                }
            }
            return localSuccess;
        }

        boolean isSuccessful() {
            return _success;
        }

        DeploymentResult finalResult() {
            return new DeploymentResult(_aid, _success, _messages);
        }
    }
    
    @ManagedAttribute
    public String getClusterType() {
        return cluster == null ? "NA" : cluster.getClass().getName();
    }
    
    @ManagedAttribute
    public String getServiceStatus() {
        return String.valueOf(_serviceState);
    }
    
    @ManagedAttribute
    public String getDiscoveredComponents() {
        return String.valueOf(_componentManagers.keySet());
    }
    
    @ManagedAttribute
    public String getMissingComponents() {
        Collection<String> missing = new HashSet<String>();
        missing.addAll(_requiredComponentManagers);
        missing.removeAll(_componentManagers.keySet());
        return String.valueOf(missing);
    }


}

/**
 * 
 * Possible Assembly States (0=missing, 1=exist/deployed)
 * 
 * Assembly .deployed .invalid Assembly 
 * Directory flag/file flag/file State Action(s)
 * ========== ========= ========= ========= ======================================================== 
 * 0 0 0 0 Nothing (no assembly) 
 * 0 0 0 1 Undeploy, remove from deploy.state 
 * 0 0 1 0 Nothing (no assembly) 
 * 0 0 1 1 Undeploy, remove from deploy.state 
 * 0 1 0 0 Nothing (no assembly) 
 * 0 1 0 1 Undeploy, remove from deploy.state 
 * 0 1 1 0 Nothing (no assembly) 
 * 0 1 1 1 Undeploy, remove from deploy.state 1 0 0 0 Deploy* 
 * 1 0 0 1 Undeploy, remove from deploy.state, deploy* 
 * 1 0 1 0 Nothing (invalid) 
 * 1 0 1 1 Undeploy, remove from deploy.state 
 * 1 1 0 0 Nothing (ignore => conservative to avoid undeploy) 
 * 1 1 0 1 Nothing (normal) 
 * 1 1 1 0 Nothing (invalid) 
 * 1 1 1 1 Nothing (already deployed?)
 * 
 * where deploy* = create .invalid, deploy, if successful, create .deployed and remove .invalid
 * 
 * In Java logic,
 * 
 * if (!assemblyDir && deploy.state) undeploy, remove from deploy.state 
 * if (assemblyDir && !.deployed && deploy.state) undeploy, remove from deploy.state 
 * if (assemblyDir && !.deployed && !.invalid && !deploy.state) deploy
 * 
 */
