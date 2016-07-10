/**
 * Copyright (c) 2007-2008 Intalio inc.
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

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.intalio.deploy.deployment.AssemblyId;
import org.intalio.deploy.deployment.ComponentId;
import org.intalio.deploy.deployment.DeployedAssembly;
import org.intalio.deploy.deployment.DeployedComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintain persistent state of deployed assemblies, components and resources.
 * <p>
 * Thread-safe
 */
public class Persistence {
    private static final Logger LOG = LoggerFactory.getLogger(Persistence.class);

    private final File _deployDir;
    
    private final DataSource _ds;
    
    // Connection held during transaction, if any.
    private ThreadLocal<Connection> _connection = new ThreadLocal<Connection>();
    
    public Persistence(File deployDir, DataSource ds) {
        _deployDir = deployDir;
        _ds = ds;
    }
   
    public void startTransaction() {
        if (_connection.get() != null) {
            rollback("Connection was already open");
            release();
            throw new IllegalStateException("Transaction already started");
        }
        try {
            _connection.set(_ds.getConnection());
            _connection.get().setAutoCommit(false);
        } catch (Exception e) {
            release();
            throw new PersistenceException(e);
        }
    }
    
    public void commitTransaction() {
        ensureTransaction();
        try {
            _connection.get().commit();
        } catch (Exception e) {
            rollback("Error during commit");
            throw new PersistenceException(e);
        } finally {
            release();
        }
    }

    public void rollbackTransaction(String reason) {
        rollback(reason);
        release();
    }
    
    /**
     * Add a deployed assembly
     */
    void add(DeployedAssembly assemblyStateFromFileSystem, List<DeployedComponent> componentsDeployment) throws PersistenceException {
    	if(LOG.isDebugEnabled()) LOG.debug("Persisting assembly: " + assemblyStateFromFileSystem + ", components: " + componentsDeployment);
    	
        String name = assemblyStateFromFileSystem.getAssemblyId().getAssemblyName();
        int version = assemblyStateFromFileSystem.getAssemblyId().getAssemblyVersion();
        String adir = _deployDir.toURI().relativize((new File(assemblyStateFromFileSystem.getAssemblyDir())).toURI()).getPath();
        boolean active = true;
        Connection c = null;
        try {
            c = getConnection();
            // Insert assembly
            if( LOG.isDebugEnabled() ) LOG.debug("INSERT_DEPLOYMENT_ASSEMBLY: " + name + "-" + version);
            EasyStatement inserta = new EasyStatement(c, "INSERT INTO DEPLOY_ASSEMBLIES VALUES (?,?,?,?)");
            try {
                inserta.write(name);
                inserta.write(version);
                inserta.write(adir);
                inserta.write(active?1:0);
                inserta.execute();
            } finally {
                inserta.close();
            }
            // Insert components
            for (DeployedComponent dc: assemblyStateFromFileSystem.getDeployedComponents()) {
                File assemblyDir = new File(assemblyStateFromFileSystem.getAssemblyDir());
                String component = dc.getComponentId().getComponentName();
                String cdir = assemblyDir.toURI().relativize(new File(dc.getComponentDir()).toURI()).getPath();
                String manager = dc.getComponentManagerName();

                if( LOG.isDebugEnabled() ) LOG.debug("INSERT_DEPLOYMENT_COMPONENT: " + name + "-" + version + "/" + component);
                EasyStatement insertc = new EasyStatement(c, "INSERT INTO DEPLOY_COMPONENTS VALUES (?,?,?,?,?)");
                try {
                    insertc.write(name);
                    insertc.write(version);
                    insertc.write(component);
                    insertc.write(manager);
                    insertc.write(cdir);
                    insertc.execute();
                } finally {
                    insertc.close();
                }
            }
            // Insert resources
        	for( DeployedComponent component : componentsDeployment ) {
        		// only if the file system says the component is deployed
        		if( assemblyStateFromFileSystem.getDeployedComponents().contains(component)) {
	        		for( String resource : component.getDeployedResources() ) {
	        			if( LOG.isDebugEnabled() ) LOG.debug("INSERT_DEPLOYMENT_RESOURCE: " + name + "-" + version + "/" + component);
	        			EasyStatement stmt = new EasyStatement(c, "INSERT INTO DEPLOY_RESOURCES(ASSEMBLY, VERSION, COMPONENT, MANAGER, RESOURCE_ID) VALUES(?, ?, ?, ?, ?)");
		                stmt.write(component.getComponentId().getAssemblyId().getAssemblyName());
		                stmt.write(component.getComponentId().getAssemblyId().getAssemblyVersion());
		                stmt.write(component.getComponentId().getComponentName());
		                stmt.write(component.getComponentManagerName());
		                stmt.write(resource);
		                stmt.execute();
		                stmt.close();
	        		}
        		}
        	}
        } catch (SQLException e) {
            throw new PersistenceException(e);
        } finally {
            close(c);
        }
    }
    
    /**
     * Retires any currently active version and activates the given version of assembly.
     * 
     * @param assembly
     * @param version
     * @throws PersistenceException
     */
    void activate(String assembly, int version) throws PersistenceException {
    	retire(assembly);
    	
        Connection c = null;
        try {
            c = getConnection();
            EasyStatement inserta = new EasyStatement(c, "UPDATE DEPLOY_ASSEMBLIES SET CACTIVE = 1 WHERE ASSEMBLY = ? AND VERSION = ?");
            try {
                inserta.write(assembly);
                inserta.write(version);
                inserta.execute();
            } finally {
                inserta.close();
            }
        } catch (SQLException e) {
            throw new PersistenceException(e);
        } finally {
            close(c);
        }
    }

    /**
     * Retire *any* currently active version of the current assembly
     */
    void retire(String assembly) throws PersistenceException {
        Connection c = null;
        try {
            c = getConnection();
            EasyStatement inserta = new EasyStatement(c, "UPDATE DEPLOY_ASSEMBLIES SET CACTIVE = 0 WHERE ASSEMBLY = ?");
            try {
                inserta.write(assembly);
                inserta.execute();
            } finally {
                inserta.close();
            }
        } catch (SQLException e) {
            throw new PersistenceException(e);
        } finally {
            close(c);
        }
    }
    
    /**
     * Retire *any* currently active version of the current assembly
     */
    void retireAssembly(AssemblyId assemblyId) throws PersistenceException {
        Connection c = null;
        try {
            c = getConnection();
            EasyStatement inserta = new EasyStatement(c, "UPDATE DEPLOY_ASSEMBLIES SET CACTIVE = 0 WHERE ASSEMBLY = ? AND VERSION = ?");
            try {
                inserta.write(assemblyId.getAssemblyName());
                inserta.write(assemblyId.getAssemblyVersion());
                inserta.execute();
            } finally {
                inserta.close();
            }
        } catch (SQLException e) {
            throw new PersistenceException(e);
        } finally {
            close(c);
        }
    }
    
    
    String fetchPIPAUrl(AssemblyId assemblyId, String processName) throws PersistenceException {
        Connection c = null;
        try {
            c = getConnection();
            EasyResultSet rs=null;
            EasyStatement selecta = new EasyStatement(
                    c,
                    "SELECT RESOURCE_ID from DEPLOY_RESOURCES WHERE ASSEMBLY = ? AND VERSION = ? AND MANAGER='pipa' AND RESOURCE_ID LIKE '%"
                            + assemblyId.getAssemblyName()
                            + "/"
                            + processName
                            + "%'");
            try {
            	selecta.write(assemblyId.getAssemblyName());
                selecta.write(assemblyId.getAssemblyVersion());
                rs=selecta.executeQuery();
                while (rs.next()) {
                    return rs.readString();                 
                }
                
                
            } finally {
            	if(rs!=null) rs.close();
            	selecta.close();
            	
            }
        } catch (SQLException e) {
            throw new PersistenceException(e);
        } finally {
            close(c);
        }
        return null;
    }

    /**
     * Load persistent deployed state
     */
    Map<AssemblyId, DeployedAssembly> load() {
        Map<AssemblyId, DeployedAssembly> assembliesById = new HashMap<AssemblyId, DeployedAssembly>();
        Map<TypedComponentId, List<String>> resourceListsByComponentId = new HashMap<TypedComponentId, List<String>>();
        Connection c = null;
        EasyResultSet rs=null;
        try {
            c = getConnection();            
            EasyStatement selecta = new EasyStatement(c, "SELECT * FROM DEPLOY_ASSEMBLIES");
            try {
                rs = selecta.executeQuery();
                while (rs.next()) {
                    String assembly = rs.readString();
                    int version = rs.readInt();
                    String adir = rs.readString();
                    boolean active = rs.readInt() != 0;
                    AssemblyId aid = new AssemblyId(assembly, version);
                    adir = (new File(_deployDir, adir)).toString();
                    DeployedAssembly da = new DeployedAssembly(aid, adir, new ArrayList<DeployedComponent>(), active);
                    assembliesById.put(aid, da);
                }
            } finally {
            	if(rs!=null) rs.close();
                selecta.close();
            }

            EasyStatement selectb = new EasyStatement(c, "SELECT * FROM DEPLOY_RESOURCES");
            try {
                rs = selectb.executeQuery();
                while (rs.next()) {
                    String assembly = rs.readString();
                    int version = rs.readInt();
                    String component = rs.readString();
                    String type = rs.readString();
                    String resource = rs.readString();
                    
                    TypedComponentId tcid = new TypedComponentId(assembly, version, component, type);
                    List<String> deployedResource = resourceListsByComponentId.get(tcid);
                    if( deployedResource == null ) {
                    	deployedResource = new ArrayList<String>();
                    	resourceListsByComponentId.put(tcid, deployedResource);
                    }
                	deployedResource.add(resource);
                }
            } finally {
            	if(rs!=null) rs.close();
                selectb.close();
            }

            EasyStatement selectc = new EasyStatement(c, "SELECT * FROM DEPLOY_COMPONENTS");
            try {
                rs = selectc.executeQuery();
                while (rs.next()) {
                    String assembly = rs.readString();
                    int version = rs.readInt();
                    String component = rs.readString();
                    String type = rs.readString();
                    String cdir = rs.readString();
                    AssemblyId aid = new AssemblyId(assembly, version);
                    ComponentId cid = new ComponentId(aid, component);
                    DeployedAssembly da = assembliesById.get(aid);
                    if (da == null) {
                        LOG.error("Deployed component entry is missing parent assembly: "+cid);
                    } else {
                        cdir = (new File(da.getAssemblyDir(), cdir)).toString();
                        List<String> deployedResources = resourceListsByComponentId.get(new TypedComponentId(cid, type));
                        if( deployedResources == null ) {
                        	deployedResources = new ArrayList<String>();
                        }
                        DeployedComponent dc = new DeployedComponent(cid, cdir, type, deployedResources);
                        da.getDeployedComponents().add(dc);
                        
                        if( LOG.isDebugEnabled() ) LOG.debug("DEPLOYMENT.component discovered: " + dc + ", resources = " + dc.getDeployedResources());
                    }
                }                
            } finally {
            	if(rs!=null) rs.close();
                selectc.close();
            }
            return assembliesById;
        } catch (SQLException e) {
            throw new PersistenceException(e);
        } finally {
            close(c);
        }
    }

    /**
     * Remove assembly from persistent state
     */
    void remove(AssemblyId aid) {
        Connection c = null;
        EasyStatement stmt;
        try {
            c = getConnection();

            stmt = new EasyStatement(c, "DELETE FROM DEPLOY_RESOURCES WHERE ASSEMBLY = ? AND VERSION = ?");
            stmt.write(aid.getAssemblyName());
            stmt.write(aid.getAssemblyVersion());
            stmt.execute();
            stmt.close();

            stmt = new EasyStatement(c, "DELETE FROM DEPLOY_COMPONENTS WHERE ASSEMBLY = ? AND VERSION = ?");
            stmt.write(aid.getAssemblyName());
            stmt.write(aid.getAssemblyVersion());
            stmt.execute();
            stmt.close();

            stmt = new EasyStatement(c, "DELETE FROM DEPLOY_ASSEMBLIES WHERE ASSEMBLY = ? AND VERSION = ?");
            stmt.write(aid.getAssemblyName());
            stmt.write(aid.getAssemblyVersion());
            stmt.execute();
            stmt.close();
        } catch (SQLException e) {
            throw new PersistenceException(e);
        } finally {
            close(c);
        }
    }

    private Connection getConnection() throws SQLException {
        Connection c = _connection.get();
        if (c == null) {
            try {
                c = _ds.getConnection();
                c.setAutoCommit(true);
            } catch (Exception e) {
                close(c);
                throw new SQLException(e.toString());
            }
        }
        return c;
    }
    
    private void ensureTransaction() {
        if (_connection.get() == null) 
            throw new IllegalStateException("No active transaction");
    }

    private void close(Connection c) {
        if (c != null && c != _connection.get()) {
            try {
                c.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    private void rollback(String reason) {
        Connection c = _connection.get();
        if (c != null) {
            LOG.warn("Deployment transaction rolled back: "+reason);
            try {
                c.rollback();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    private void release() {
        Connection c = _connection.get();
        if (c != null) {
            try {
                c.setAutoCommit(true);
            } catch (SQLException e) {
                // ignore
            }
            try {
                c.close();
            } catch (SQLException e) {
                // ignore
            }
            _connection.set(null);
        }
    }
    
    
    static class TypedComponentId extends ComponentId {
		private static final long serialVersionUID = 1L;
		
		private String _componentType;

    	public TypedComponentId(String assemblyName, int assemblyVersion, String componentName, String componentType) {
    		super(new AssemblyId(assemblyName, assemblyVersion), componentName);
            if (componentType == null) throw new IllegalArgumentException("Component type name cannot be null");
            
            _componentType = componentType;
        }

    	public TypedComponentId(ComponentId componentId, String componentType) {
    		super(componentId.getAssemblyId(), componentId.getComponentName());
            if (componentType == null) throw new IllegalArgumentException("Component type name cannot be null");
            
            _componentType = componentType;
        }

        public String getComponentType() {
			return _componentType;
		}

		public boolean equals(Object obj) {
            if (obj instanceof TypedComponentId) {
            	TypedComponentId other = (TypedComponentId) obj;
            	return super.equals(obj) && _componentType.equals(other._componentType);
            } else {
                return false;
            }
        }

        public int hashCode() {
            return super.hashCode() + _componentType.hashCode() * 13;
        }
        
        public String toString() {
            return super.toString() + "." + _componentType;
        }
    }


	public int getAssemblyVersion(String assemblyName) {
		Connection c = null;
        try {
            c = getConnection();
            EasyResultSet rs=null;
            EasyStatement selecta = new EasyStatement(c, "select max(version) from DEPLOY_ASSEMBLIES WHERE assembly='"+ assemblyName+"''");            
            try {
            	selecta.write(assemblyName);                
                rs=selecta.executeQuery();
                while (rs.next()) {
                    return rs.readInt();                 
                }
                
                
            } finally {
            	if(rs!=null) rs.close();
            	selecta.close();
            	
            }
        } catch (SQLException e) {
            throw new PersistenceException(e);
        } finally {
            close(c);
        }
        return -1;
	}
}
