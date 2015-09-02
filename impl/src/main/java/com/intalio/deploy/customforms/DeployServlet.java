/**
 * Copyright (C) 1999-2015, Intalio Inc.
 *
 * The program(s) herein may be used and/or copied only with
 * the written permission of Intalio Inc. or in accordance with
 * the terms and conditions stipulated in the agreement/contract
 * under which the program(s) have been supplied.
 */
package com.intalio.deploy.customforms;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;

import org.intalio.deploy.deployment.utils.DeploymentServiceRegister;

/**
 * The servlet class to register Customforms ComponentManager to the
 * DeploymentService
 * 
 * @author Cyril antony
 */
public class DeployServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    protected ComponentManager _manager;
    protected DeploymentServiceRegister _register;

    @Override
    public void init() throws ServletException {

        _manager = new ComponentManager();
        _register = new DeploymentServiceRegister(_manager);
        _register.init();
    }

    @Override
    public void destroy() {
        super.destroy();

        if (_register != null) {
            _register.destroy();
        }
    }
}
