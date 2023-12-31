/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.dfs;

import java.util.*;
import java.io.*;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.mapred.StatusHttpServer;
import org.apache.commons.logging.*;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


/**
 * This class is used in Namesystem's jetty to retrieve a file.
 * Typically used by the Secondary NameNode to retrieve image and
 * edit file for periodic checkpointing.
 * @author Dhruba Borthakur
 */
public class GetImageServlet extends HttpServlet {

  private static final Log LOG = LogFactory.getLog("org.apache.hadoop.dfs.FSNamesystem");

  @SuppressWarnings("unchecked")
  public void doGet(HttpServletRequest request,
                    HttpServletResponse response
                    ) throws ServletException, IOException {
    Map<String,String[]> pmap = request.getParameterMap();
    try {
      ServletContext context = getServletContext();
      NameNode nn = (NameNode) context.getAttribute("name.node");
      TransferFsImage ff = new TransferFsImage(pmap, request, response);
      if (ff.getImage()) {
        // send fsImage to Secondary
        TransferFsImage.getFileServer(response.getOutputStream(),
                                      nn.getFsImageName()); 
      } else if (ff.getEdit()) {
        // send old edits to Secondary
        TransferFsImage.getFileServer(response.getOutputStream(),
                                      nn.getFsEditName());
      } else if (ff.putImage()) {
        // issue a HTTP get request to download the new fsimage 
        TransferFsImage.getFileClient(ff.getInfoServer(), "getimage=1", 
                                      nn.getFsImageNameCheckpoint());
      }
    } catch (IOException ie) {
      StringUtils.stringifyException(ie);
      LOG.warn(ie);
      String errMsg = "GetImage failed.";
      response.sendError(HttpServletResponse.SC_GONE, errMsg);
      throw ie;
    }
  }
}
