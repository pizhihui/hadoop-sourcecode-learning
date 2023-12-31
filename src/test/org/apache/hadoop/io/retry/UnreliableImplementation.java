package org.apache.hadoop.io.retry;

import org.apache.hadoop.ipc.RemoteException;

public class UnreliableImplementation implements UnreliableInterface {

  private int failsOnceInvocationCount,
    failsOnceWithValueInvocationCount,
    failsTenTimesInvocationCount;
  
  public void alwaysSucceeds() {
    // do nothing
  }
  
  public void alwaysFailsWithFatalException() throws FatalException {
    throw new FatalException();
  }
  
  public void alwaysFailsWithRemoteFatalException() throws RemoteException {
    throw new RemoteException(FatalException.class.getName(), "Oops");
  }

  public void failsOnceThenSucceeds() throws UnreliableException {
    if (failsOnceInvocationCount++ == 0) {
      throw new UnreliableException();
    }
  }

  public boolean failsOnceThenSucceedsWithReturnValue() throws UnreliableException {
    if (failsOnceWithValueInvocationCount++ == 0) {
      throw new UnreliableException();
    }
    return true;
  }

  public void failsTenTimesThenSucceeds() throws UnreliableException {
    if (failsTenTimesInvocationCount++ < 10) {
      throw new UnreliableException();
    }
  }

}
