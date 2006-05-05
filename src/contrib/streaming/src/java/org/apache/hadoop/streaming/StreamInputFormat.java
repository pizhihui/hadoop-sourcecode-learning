/**
 * Copyright 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.streaming;

import java.io.*;
import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.*;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.FSDataInputStream;

import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.UTF8;

import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.LogFormatter;


/** An input format that performs globbing on DFS paths and 
 * selects a RecordReader based on a JobConf property.
 * @author Michel Tourn
 */
public class StreamInputFormat extends InputFormatBase
{

  // an InputFormat should be public with the synthetic public default constructor
  // JobTracker's JobInProgress will instantiate with clazz.newInstance() (and a custom ClassLoader)
  
  protected static final Logger LOG = LogFormatter.getLogger(StreamInputFormat.class.getName());
  static {
    //LOG.setLevel(Level.FINE);
  }
  
  protected Path[] listPaths(FileSystem fs, JobConf job)
    throws IOException
  {
    Path[] globs = job.getInputPaths();
    ArrayList list = new ArrayList();
    int dsup = globs.length;
    for(int d=0; d<dsup; d++) {
      String leafName = globs[d].getName();
      LOG.fine("StreamInputFormat: globs[" + d + "] leafName = " + leafName);
      Path[] paths; Path dir;
	  PathFilter filter = new GlobFilter(fs, leafName);
	  dir = new Path(globs[d].getParent().toString());
      if(dir == null) dir = new Path(".");
	  paths = fs.listPaths(dir, filter);
      list.addAll(Arrays.asList(paths));
    }
    return (Path[])list.toArray(new Path[]{});
  }

  class GlobFilter implements PathFilter
  {
    public GlobFilter(FileSystem fs, String glob)
    {
      fs_ = fs;
      pat_ = Pattern.compile(globToRegexp(glob));
    }
    String globToRegexp(String glob)
	{
	  return glob.replaceAll("\\*", ".*");
	}

    public boolean accept(Path pathname)
    {
      boolean acc = !fs_.isChecksumFile(pathname);
      if(acc) {
      	acc = pat_.matcher(pathname.getName()).matches();
      }
      LOG.finer("matches " + pat_ + ", " + pathname + " = " + acc);
      return acc;
    }
	
	Pattern pat_;
    FileSystem fs_;
  }

  public RecordReader getRecordReader(FileSystem fs, final FileSplit split,
                                      JobConf job, Reporter reporter)
    throws IOException {
    LOG.finer("getRecordReader start.....");
    reporter.setStatus(split.toString());

    final long start = split.getStart();
    final long end = start + split.getLength();

    String splitName = split.getFile() + ":" + start + "-" + end;
    final FSDataInputStream in = fs.open(split.getFile());
    
    // will open the file and seek to the start of the split
    // Factory dispatch based on available params..    
    Class readerClass;
    String c = job.get("stream.recordreader.class");
    if(c == null) {
      readerClass = StreamLineRecordReader.class;
    } else {
      readerClass = StreamUtil.goodClassOrNull(c, null);
      if(readerClass == null) {
        throw new RuntimeException("Class not found: " + c);
      }    
    }
    
    Constructor ctor;
    try {
      // reader = new StreamLineRecordReader(in, start, end, splitName, reporter, job);
      ctor = readerClass.getConstructor(new Class[]{
        FSDataInputStream.class, long.class, long.class, String.class, Reporter.class, JobConf.class});
    } catch(NoSuchMethodException nsm) {
      throw new RuntimeException(nsm);
    }

    
    StreamBaseRecordReader reader;
    try {
        reader = (StreamBaseRecordReader) ctor.newInstance(new Object[]{
            in, new Long(start), new Long(end), splitName, reporter, job});        
    } catch(Exception nsm) {
      throw new RuntimeException(nsm);
    }
        
	reader.init();
    
    return reader;
  }
  
}