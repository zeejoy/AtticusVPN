/* Copyright (c) 2026 TianzeL; SPDX-License-Identifier: Apache-2.0 */
package dev.lan.atticusvpn.server.logging;
import android.app.Application;
import dev.lan.atticusvpn.server.model.ServerState;
import dev.lan.atticusvpn.server.storage.EncryptedStateRepository;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35},application=Application.class)
public class RuntimeLogCollectorGatewayTest {
 @Test public void nativeGatewayErrorsReachRenderedModelAndIgnoreRawReason() throws Exception {
  EncryptedStateRepository repository=new EncryptedStateRepository(RuntimeEnvironment.getApplication());
  ReflectionHelpers.setField(repository,"state",new ServerState());
  java.lang.reflect.Constructor<RuntimeLogCollector> ctor=RuntimeLogCollector.class.getDeclaredConstructor();ctor.setAccessible(true);
  RuntimeLogCollector collector=ctor.newInstance();
  String json="{\"startedUnix\":1,\"running\":true,\"peers\":[],\"errors\":[{\"code\":\"GATEWAY_TCP_DIAL_FAILED\",\"count\":3,\"reason\":\"TEST_ONLY_PRIVATE_TARGET\",\"lastUnix\":1},{\"code\":\"GATEWAY_DIAL_TIMEOUT\",\"count\":1,\"lastUnix\":1}]}";
  collector.nativeSnapshot(RunLogModel.Protocol.WG,json,repository);
  collector.nativeSnapshot(RunLogModel.Protocol.WG,json,repository);
  RunLogModel model=ReflectionHelpers.getField(collector,"model");
  String rendered=model.render(false,2000);
  assertTrue(rendered.contains("GATEWAY_TCP_DIAL_FAILED; count=3"));
  assertTrue(rendered.contains("GATEWAY_DIAL_TIMEOUT —"));
  assertEquals(rendered.indexOf("GATEWAY_DIAL_TIMEOUT"),rendered.lastIndexOf("GATEWAY_DIAL_TIMEOUT"));
  assertFalse(rendered.contains("count=6"));assertFalse(rendered.contains("TEST_ONLY_PRIVATE_TARGET"));
 }
}
