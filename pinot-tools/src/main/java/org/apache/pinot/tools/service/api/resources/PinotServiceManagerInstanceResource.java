/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pinot.tools.service.api.resources;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.pinot.common.utils.CommonConstants;
import org.apache.pinot.common.utils.NetUtil;
import org.apache.pinot.controller.ControllerConf;
import org.apache.pinot.spi.services.ServiceRole;
import org.apache.pinot.spi.utils.JsonUtils;
import org.apache.pinot.tools.service.PinotServiceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.pinot.common.utils.CommonConstants.Controller.CONFIG_OF_CONTROLLER_METRICS_PREFIX;
import static org.apache.pinot.common.utils.CommonConstants.Controller.DEFAULT_METRICS_PREFIX;
import static org.apache.pinot.tools.utils.PinotConfigUtils.TMP_DIR;
import static org.apache.pinot.tools.utils.PinotConfigUtils.getAvailablePort;


@Api(tags = "Startable")
@Path("/")
public class PinotServiceManagerInstanceResource {

  private static final Logger LOGGER = LoggerFactory.getLogger(PinotServiceManagerInstanceResource.class);

  @Inject
  private PinotServiceManager _pinotServiceManager;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/instances")
  @ApiOperation(value = "Get Pinot Instances Status")
  @ApiResponses(value = {@ApiResponse(code = 200, message = "Instance Status"), @ApiResponse(code = 500, message = "Internal server error")})
  public Map<String, PinotInstanceStatus> getPinotAllInstancesStatus() {
    Map<String, PinotInstanceStatus> results = new HashMap<>();
    for (String instanceId : _pinotServiceManager.getRunningInstanceIds()) {
      results.put(instanceId, _pinotServiceManager.getInstanceStatus(instanceId));
    }
    return results;
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/instances/{instanceName}")
  @ApiOperation(value = "Get Pinot Instance Status")
  @ApiResponses(value = {@ApiResponse(code = 200, message = "Instance Status"), @ApiResponse(code = 404, message = "Instance Not Found"), @ApiResponse(code = 500, message = "Internal server error")})
  public PinotInstanceStatus getPinotInstanceStatus(
      @ApiParam(value = "Name of the instance") @PathParam("instanceName") String instanceName) {
    List<String> instanceIds = _pinotServiceManager.getRunningInstanceIds();
    if (instanceIds.contains(instanceName)) {
      return _pinotServiceManager.getInstanceStatus(instanceName);
    }
    throw new WebApplicationException(String.format("Instance [%s] not found.", instanceName),
        Response.Status.NOT_FOUND);
  }

  @DELETE
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/instances/{instanceName}")
  @ApiOperation(value = "Stop a Pinot Instance")
  @ApiResponses(value = {@ApiResponse(code = 200, message = "Pinot Instance is Stopped"), @ApiResponse(code = 404, message = "Instance Not Found"), @ApiResponse(code = 500, message = "Internal server error")})
  public Response stopPinotInstance(
      @ApiParam(value = "Name of the instance") @PathParam("instanceName") String instanceName) {
    List<String> instanceIds = _pinotServiceManager.getRunningInstanceIds();
    if (instanceIds.contains(instanceName)) {
      if (_pinotServiceManager.stopPinotInstanceById(instanceName)) {
        return Response.ok().build();
      } else {
        throw new WebApplicationException(String.format("Failed to stop a Pinot instance [%s]", instanceName),
            Response.Status.INTERNAL_SERVER_ERROR);
      }
    }
    throw new WebApplicationException(String.format("Instance [%s] not found.", instanceName),
        Response.Status.NOT_FOUND);
  }

  @POST
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/instances/{role}")
  @ApiOperation(value = "Start a Pinot instance")
  @ApiResponses(value = {@ApiResponse(code = 200, message = "Pinot instance is started"), @ApiResponse(code = 400, message = "Bad Request"), @ApiResponse(code = 404, message = "Pinot Role Not Found"), @ApiResponse(code = 500, message = "Internal Server Error")})
  public PinotInstanceStatus startPinotInstance(
      @ApiParam(value = "A Role of Pinot Instance to start: CONTROLLER/BROKER/SERVER") @PathParam("role") String role,
      @ApiParam(value = "true|false") @QueryParam("autoMode") boolean autoMode, String confStr) {
    ServiceRole serviceRole;
    try {
      serviceRole = ServiceRole.valueOf(role.toUpperCase());
    } catch (Exception e) {
      throw new WebApplicationException("Unrecognized Role: " + role, Response.Status.NOT_FOUND);
    }
    Configuration configuration;
    try {
      configuration = JsonUtils.stringToObject(confStr, Configuration.class);
    } catch (IOException e) {
      if (autoMode) {
        switch (serviceRole) {
          case CONTROLLER:
            configuration = new ControllerConf();
            break;
          default:
            configuration = new PropertiesConfiguration();
        }
      } else {
        throw new WebApplicationException("Unable to deserialize Conf String to Configuration Object",
            Response.Status.BAD_REQUEST);
      }
    }
    if (autoMode) {
      updateConfiguration(serviceRole, configuration);
    }
    try {
      String instanceName = _pinotServiceManager.startRole(serviceRole, configuration);
      if (instanceName != null) {
        LOGGER.info("Successfully started Pinot [{}] instance [{}]", serviceRole, instanceName);
        return _pinotServiceManager.getInstanceStatus(instanceName);
      }
      throw new WebApplicationException(String.format("Unable to start a Pinot [%s]", serviceRole),
          Response.Status.INTERNAL_SERVER_ERROR);
    } catch (Exception e) {
      LOGGER.error("Caught exception while processing POST request", e);
      throw new WebApplicationException(e, Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  private void updateConfiguration(ServiceRole role, Configuration configuration) {
    switch (role) {
      case CONTROLLER:
        ControllerConf controllerConf = (ControllerConf) configuration;
        if (controllerConf.getControllerHost() == null) {
          String hostname;
          try {
            hostname = NetUtil.getHostAddress();
          } catch (Exception e) {
            hostname = "localhost";
          }
          controllerConf.setControllerHost(hostname);
        }
        if (controllerConf.getControllerPort() == null) {
          controllerConf.setControllerPort(Integer.toString(getAvailablePort()));
        }
        if (controllerConf.getDataDir() == null) {
          controllerConf.setDataDir(TMP_DIR + String
              .format("Controller_%s_%s/data", controllerConf.getControllerHost(), controllerConf.getControllerPort()));
        }
        if (!controllerConf.containsKey(CONFIG_OF_CONTROLLER_METRICS_PREFIX)) {
          controllerConf.addProperty(CONFIG_OF_CONTROLLER_METRICS_PREFIX, String
              .format("%s.%s_%s", DEFAULT_METRICS_PREFIX, controllerConf.getControllerHost(),
                  controllerConf.getControllerPort()));
        }
        break;
      case BROKER:
        if (!configuration.containsKey(CommonConstants.Helix.KEY_OF_BROKER_QUERY_PORT)) {
          configuration.addProperty(CommonConstants.Helix.KEY_OF_BROKER_QUERY_PORT, getAvailablePort());
        }
        if (!configuration.containsKey(CommonConstants.Broker.METRICS_CONFIG_PREFIX)) {
          String hostname;
          try {
            hostname = NetUtil.getHostAddress();
          } catch (Exception e) {
            hostname = "localhost";
          }
          configuration.addProperty(CommonConstants.Broker.CONFIG_OF_METRICS_NAME_PREFIX, String
              .format("%s%s_%d", CommonConstants.Broker.DEFAULT_METRICS_NAME_PREFIX, hostname,
                  configuration.getInt(CommonConstants.Helix.KEY_OF_BROKER_QUERY_PORT)));
        }
        return;
      case SERVER:
        if (!configuration.containsKey(CommonConstants.Helix.KEY_OF_SERVER_NETTY_HOST)) {
          String hostname;
          try {
            hostname = NetUtil.getHostAddress();
          } catch (Exception e) {
            hostname = "localhost";
          }
          configuration.addProperty(CommonConstants.Helix.KEY_OF_SERVER_NETTY_HOST, hostname);
        }
        if (!configuration.containsKey(CommonConstants.Helix.KEY_OF_SERVER_NETTY_PORT)) {
          configuration.addProperty(CommonConstants.Helix.KEY_OF_SERVER_NETTY_PORT, getAvailablePort());
        }
        if (!configuration.containsKey(CommonConstants.Server.CONFIG_OF_ADMIN_API_PORT)) {
          configuration.addProperty(CommonConstants.Server.CONFIG_OF_ADMIN_API_PORT, getAvailablePort());
        }
        if (!configuration.containsKey(CommonConstants.Server.CONFIG_OF_INSTANCE_DATA_DIR)) {
          configuration.addProperty(CommonConstants.Server.CONFIG_OF_INSTANCE_DATA_DIR, TMP_DIR + String
              .format("Server_%s_%d/data", configuration.getString(CommonConstants.Helix.KEY_OF_SERVER_NETTY_HOST),
                  configuration.getInt(CommonConstants.Helix.KEY_OF_SERVER_NETTY_PORT)));
        }
        if (!configuration.containsKey(CommonConstants.Server.CONFIG_OF_INSTANCE_SEGMENT_TAR_DIR)) {
          configuration.addProperty(CommonConstants.Server.CONFIG_OF_INSTANCE_SEGMENT_TAR_DIR, TMP_DIR + String
              .format("Server_%s_%d/segment", configuration.getString(CommonConstants.Helix.KEY_OF_SERVER_NETTY_HOST),
                  configuration.getInt(CommonConstants.Helix.KEY_OF_SERVER_NETTY_PORT)));
        }
        if (!configuration.containsKey(CommonConstants.Server.PINOT_SERVER_METRICS_PREFIX)) {
          configuration.addProperty(CommonConstants.Server.PINOT_SERVER_METRICS_PREFIX, String
              .format("%s%s_%d", CommonConstants.Server.DEFAULT_METRICS_PREFIX,
                  configuration.getString(CommonConstants.Helix.KEY_OF_SERVER_NETTY_HOST),
                  configuration.getInt(CommonConstants.Helix.KEY_OF_SERVER_NETTY_PORT)));
        }
        return;
    }
  }
}
