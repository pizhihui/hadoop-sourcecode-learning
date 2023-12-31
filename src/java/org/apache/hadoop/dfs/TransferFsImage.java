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

import java.io.*;
import java.net.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletRequest;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.UTF8;


/**
 * This class provides fetching a specified file from the NameNode.
 * @author Dhruba Borthakur
 */
class TransferFsImage implements FSConstants {
  
  private HttpServletResponse response;
  private boolean isGetImage;
  private boolean isGetEdit;
  private boolean isPutImage;
  private int remoteport;
  private String machineName;
  
  /**
   * File downloader.
   * @param pmap key=value[] map that is passed to the http servlet as 
   *        url parameters
   * @param request the object from which this servelet reads the url contents
   * @param response the object into which this servelet writes the url contents
   * @throws IOException
   */
  public TransferFsImage(Map<String,String[]> pmap,
                         HttpServletRequest request,
                         HttpServletResponse response
                         ) throws IOException {
    isGetImage = isGetEdit = isPutImage = false;
    remoteport = 0;
    machineName = null;

    for (Iterator<String> it = pmap.keySet().iterator(); it.hasNext();) {
      String key = it.next();
      if (key.equals("getimage")) { 
        isGetImage = true;
      } else if (key.equals("getedit")) { 
        isGetEdit = true;
      } else if (key.equals("putimage")) { 
        isPutImage = true;
      } else if (key.equals("port")) { 
        remoteport = new Integer(pmap.get("port")[0]).intValue();
      } else if (key.equals("machine")) { 
        machineName = pmap.get("machine")[0];
      }
    }
    if ((isGetImage && isGetEdit) ||
        (!isGetImage && !isGetEdit && !isPutImage)) {
      throw new IOException("No good parameters to TransferFsImage");
    }
  }

  boolean getEdit() {
    return isGetEdit;
  }

  boolean getImage() {
    return isGetImage;
  }

  boolean putImage() {
    return isPutImage;
  }

  String getInfoServer() throws IOException{
    if (machineName == null || remoteport == 0) {
      throw new IOException ("MachineName and port undefined");
    }
    return machineName + ":" + remoteport;
  }

  /**
   * A server-side method to respond to a getfile http request
   * Copies the contents of the local file into the output stream.
   */
  static void getFileServer(OutputStream outstream, File localfile) 
    throws IOException {
    byte buf[] = new byte[BUFFER_SIZE];
    FileInputStream infile = null;
    try {
      infile = new FileInputStream(localfile);
      int num = 1;
      while (num > 0) {
        num = infile.read(buf);
        if (num <= 0) {
          break;
        }
        outstream.write(buf, 0, num);
      }
    } finally {
      outstream.close();
      if (infile != null) {
        infile.close();
      }
    }
  }

  /**
   * Client-side Method to fetch file from a server
   * Copies the response from the URL to a list of local files.
   */
  static void getFileClient(String fsName, String id, File[] localPath)
    throws IOException {
    byte[] buf = new byte[BUFFER_SIZE];
    StringBuffer str = new StringBuffer("http://"+fsName+"/getimage?");
    str.append(id);

    //
    // open connection to remote server
    //
    URL url = new URL(str.toString());
    URLConnection connection = url.openConnection();
    InputStream stream = connection.getInputStream();
    FileOutputStream[] output = null;
    if (localPath != null) {
      output = new FileOutputStream[localPath.length];
      for (int i = 0; i < output.length; i++) {
        output[i] = new FileOutputStream(localPath[i]);
      }
    }

    try {
      int num = 1;
      while (num > 0) {
        num = stream.read(buf);
        if (num > 0 && localPath != null) {
          for (int i = 0; i < output.length; i++) {
            output[i].write(buf, 0, num);
          }
        }
      }
    } finally {
      stream.close();
      if (localPath != null) {
        for (int i = 0; i < output.length; i++) {
          output[i].close();
        }
      }
    }
  }

  /**
   * Client-side Method to fetch file from a server
   * Copies the response from the URL to the local file.
   */
  static void getFileClient(String fsName, String id, File localPath)
    throws IOException {
    File[] filelist = new File[1];
    filelist[0] = localPath;
    getFileClient(fsName, id, filelist);
  }
}
