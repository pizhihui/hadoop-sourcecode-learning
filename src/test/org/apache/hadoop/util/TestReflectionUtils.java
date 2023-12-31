package org.apache.hadoop.util;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;

import junit.framework.TestCase;

public class TestReflectionUtils extends TestCase {

  private static Class toConstruct[] = { String.class, TestReflectionUtils.class, HashMap.class };
  private Throwable failure = null;

  public void setUp() {
    ReflectionUtils.clearCache();
  }
    
  public void testCache() throws Exception {
    assertEquals(0, cacheSize());
    doTestCache();
    assertEquals(toConstruct.length, cacheSize());
    ReflectionUtils.clearCache();
    assertEquals(0, cacheSize());
  }
    
    
  private void doTestCache() {
    for (int i=0; i<toConstruct.length; i++) {
      Class cl = toConstruct[i];
      Object x = ReflectionUtils.newInstance(cl, null);
      Object y = ReflectionUtils.newInstance(cl, null);
      assertEquals(cl, x.getClass());
      assertEquals(cl, y.getClass());
    }
  }
    
  public void testThreadSafe() throws Exception {
    Thread[] th = new Thread[32];
    for (int i=0; i<th.length; i++) {
      th[i] = new Thread() {
          public void run() {
            try {
              doTestCache();
            } catch (Throwable t) {
              failure = t;
            }
          }
        };
      th[i].start();
    }
    for (int i=0; i<th.length; i++) {
      th[i].join();
    }
    if (failure != null) {
      failure.printStackTrace();
      fail(failure.getMessage());
    }
  }
    
  private int cacheSize() throws Exception {
    return ReflectionUtils.getCacheSize();
  }
    
  public void testCantCreate() {
    try {
      ReflectionUtils.newInstance(NoDefaultCtor.class, null);
      fail("invalid call should fail");
    } catch (RuntimeException rte) {
      assertEquals(NoSuchMethodException.class, rte.getCause().getClass());
    }
  }
    
  public void testCacheDoesntLeak() throws Exception {
    int iterations=9999; // very fast, but a bit less reliable - bigger numbers force GC
    for (int i=0; i<iterations; i++) {
      URLClassLoader loader = new URLClassLoader(new URL[0], getClass().getClassLoader());
      Class cl = Class.forName("org.apache.hadoop.util.TestReflectionUtils$LoadedInChild", false, loader);
      Object o = ReflectionUtils.newInstance(cl, null);
      assertEquals(cl, o.getClass());
    }
    System.gc();
    assertTrue(cacheSize()+" too big", cacheSize()<iterations);
  }
    
  private static class LoadedInChild {
  }
    
  public static class NoDefaultCtor {
    public NoDefaultCtor(int x) {}
  }
}
