package org.intalio.deploy.deployment.impl.clustering;

import org.intalio.deploy.deployment.DeployedAssembly;

/**
 * Through this interface, a deployment node is notified on deployment related events.
 * 
 * @author sean
 *
 */
public interface ClusterListener {
    /**
     * Called when the coordinator deploys a new assembly.
     * 
     * @param assembly
     * @param activate when set to true, the version should also be activated
     */
    void onDeployed(DeployedAssembly assembly, boolean activate);
    
    /**
     * Called when the coordinator un-deploys an assembly.
     * 
     * @param assembly
     * @param avoidCalling when set to true it avoids sending message to component manager.
     */
    void onUndeployed(DeployedAssembly assembly, boolean avoidCalling);
    
    /** 
     * Called when the coordinator activates the assembly version.
     * 
     * @param assembly
     */
    void onActivated(DeployedAssembly assembly);

    /**
     * Called when the coordinator retires the assembly version.
     * 
     * @param assembly
     */
    void onRetired(DeployedAssembly assembly);
}
