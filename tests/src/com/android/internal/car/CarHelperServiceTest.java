/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.car;

import static com.android.car.internal.CommonConstants.CAR_SERVICE_INTERFACE;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STARTING;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPED;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_STOPPING;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_SWITCHING;
import static com.android.car.internal.CommonConstants.USER_LIFECYCLE_EVENT_TYPE_UNLOCKING;
import static com.android.car.internal.SystemConstants.ICAR_SYSTEM_SERVER_CLIENT;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

import android.annotation.UserIdInt;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.userlib.CarUserManagerHelper;
import android.car.watchdoglib.CarWatchdogDaemonHelper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.internal.ICarSystemServerClient;
import com.android.internal.os.IResultReceiver;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;
import com.android.server.wm.CarLaunchParamsModifier;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

/**
 * This class contains unit tests for the {@link CarServiceHelperService}.
 */
@RunWith(AndroidJUnit4.class)
public class CarHelperServiceTest extends AbstractExtendedMockitoTestCase {

    private static final String TAG = CarHelperServiceTest.class.getSimpleName();

    private CarServiceHelperService mHelperSpy;
    private CarServiceHelperService mHelper;
    private FakeICarSystemServerClient mCarService;

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Context mApplicationContext;
    @Mock
    private CarUserManagerHelper mUserManagerHelper;
    @Mock
    private UserManager mUserManager;
    @Mock
    private CarLaunchParamsModifier mCarLaunchParamsModifier;
    @Mock
    private CarWatchdogDaemonHelper mCarWatchdogDaemonHelper;
    @Mock
    private IBinder mICarBinder;
    @Mock
    private CarServiceProxy mCarServiceProxy;

    @Captor
    private ArgumentCaptor<Parcel> mBinderCallData;

    private Exception mBinderCallException;

    /**
     * Initialize objects and setup testing environment.
     */
    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(ServiceManager.class);
    }

    @Before
    public void setUpMocks() {
        mHelper = new CarServiceHelperService(
                mMockContext,
                mUserManagerHelper,
                mUserManager,
                mCarLaunchParamsModifier,
                mCarWatchdogDaemonHelper,
                mCarServiceProxy);
        mHelperSpy = spy(mHelper);
        mCarService = new FakeICarSystemServerClient();
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        doNothing().when(
                () -> ServiceManager.addService(eq(CarServiceHelperService.DUMP_SERVICE), any()));
    }

    @Test
    public void testCarServiceLaunched() throws Exception {
        mockRegisterReceiver();
        mockBindService();
        mockLoadLibrary();

        mHelperSpy.onStart();

        verifyBindService();
    }

    @Test
    public void testHandleCarServiceCrash() throws Exception {
        mockHandleCarServiceCrash();
        mockCarServiceException();

        mHelperSpy.handleCarServiceConnection(mICarBinder);

        verify(mHelperSpy).handleCarServiceCrash();
    }

    @Test
    public void testOnUserStarting_notifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        long before = System.currentTimeMillis();
        mHelper.onUserStarting(newTargetUser(userId));

        assertNoICarCallExceptions();

        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_STARTING, before,
                UserHandle.USER_NULL, userId);
    }

    @Test
    public void testOnUserStarting_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserStarting(newTargetUser(10, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testOnUserSwitching_notifiesICar() throws Exception {
        bindMockICar();

        int currentUserId = 10;
        int targetUserId = 11;
        long before = System.currentTimeMillis();

        mHelper.onUserSwitching(newTargetUser(currentUserId),
                newTargetUser(targetUserId));

        assertNoICarCallExceptions();
        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_SWITCHING, before,
                currentUserId, targetUserId);
    }

    @Test
    public void testOnUserSwitching_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserSwitching(newTargetUser(10), newTargetUser(11, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testOnUserUnlocking_notifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        long before = System.currentTimeMillis();

        mHelper.onUserUnlocking(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_UNLOCKING, before,
                UserHandle.USER_NULL, userId);
    }

    @Test
    public void testOnUserUnlocking_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserUnlocking(newTargetUser(10, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testOnUserStopping_notifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        long before = System.currentTimeMillis();

        mHelper.onUserStopping(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_STOPPING, before,
                UserHandle.USER_NULL, userId);
    }

    @Test
    public void testOnUserStopping_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserStopping(newTargetUser(10, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testOnUserStopped_notifiesICar() throws Exception {
        bindMockICar();

        int userId = 10;
        long before = System.currentTimeMillis();

        mHelper.onUserStopped(newTargetUser(userId));

        assertNoICarCallExceptions();
        verifyICarOnUserLifecycleEventCalled(USER_LIFECYCLE_EVENT_TYPE_STOPPED, before,
                UserHandle.USER_NULL, userId);
    }

    @Test
    public void testOnUserStopped_preCreatedDoesntNotifyICar() throws Exception {
        bindMockICar();

        mHelper.onUserStopped(newTargetUser(10, /* preCreated= */ true));

        verifyICarOnUserLifecycleEventNeverCalled();
    }

    @Test
    public void testOnBootPhase_thirdPartyCanStart_initBootUser() throws Exception {
        mHelperSpy.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        verifyInitBootUser();
    }

    @Test
    public void testOnBootPhase_onBootCompleted_preCreatedUsers() throws Exception {
        mHelperSpy.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);

        verifyPreCreatedUsers();
    }

    private TargetUser newTargetUser(int userId) {
        return newTargetUser(userId, /* preCreated= */ false);
    }

    private TargetUser newTargetUser(int userId, boolean preCreated) {
        TargetUser targetUser = mock(TargetUser.class);
        when(targetUser.getUserIdentifier()).thenReturn(userId);
        when(targetUser.isPreCreated()).thenReturn(preCreated);
        return targetUser;
    }

    private void bindMockICar() throws Exception {
        // Must set the binder expectation, otherwise checks for other transactions would fail
        expectSetSystemServerConnections();
        mHelper.handleCarServiceConnection(mICarBinder);
    }

    private void verifyBindService () throws Exception {
        verify(mMockContext).bindServiceAsUser(
                argThat(intent -> intent.getAction().equals(CAR_SERVICE_INTERFACE)),
                any(), eq(Context.BIND_AUTO_CREATE), any(), eq(UserHandle.SYSTEM));
    }

    private void mockRegisterReceiver() {
        when(mMockContext.registerReceiverForAllUsers(any(), any(), any(), any()))
                .thenReturn(new Intent());
    }

    private void mockBindService() {
        when(mMockContext.bindServiceAsUser(any(), any(),
                eq(Context.BIND_AUTO_CREATE), any(), eq(UserHandle.SYSTEM)))
                .thenReturn(true);
    }

    private void mockLoadLibrary() {
        doNothing().when(mHelperSpy).loadNativeLibrary();
    }

    private void mockCarServiceException() throws Exception {
        when(mICarBinder.transact(anyInt(), notNull(), isNull(), eq(Binder.FLAG_ONEWAY)))
                .thenThrow(new RemoteException("mock car service Crash"));
    }

    private void mockHandleCarServiceCrash() throws Exception {
        doNothing().when(mHelperSpy).handleCarServiceCrash();
    }

    private void expectSetSystemServerConnections() throws Exception {
        int txn = IBinder.FIRST_CALL_TRANSACTION;
        when(mICarBinder.transact(eq(txn), notNull(), isNull(), eq(Binder.FLAG_ONEWAY)))
                .thenAnswer((invocation) -> {
                    Log.d(TAG, "Answering txn " + txn);
                    Parcel data = (Parcel) invocation.getArguments()[1];
                    data.setDataPosition(0);
                    data.enforceInterface(CAR_SERVICE_INTERFACE);
                    data.readStrongBinder(); // helper
                    IBinder result = data.readStrongBinder();
                    IResultReceiver resultReceiver = IResultReceiver.Stub.asInterface(result);
                    Bundle bundle = new Bundle();
                    IBinder binder = mCarService.asBinder();
                    bundle.putBinder(ICAR_SYSTEM_SERVER_CLIENT, binder);
                    resultReceiver.send(1, bundle);
                    return true;
                });
    }

    enum InitialUserInfoAction {
        DEFAULT,
        DEFAULT_WITH_LOCALE,
        DO_NOT_REPLY,
        DELAYED_REPLY,
        NON_OK_RESULT_CODE,
        NULL_BUNDLE,
        SWITCH_OK,
        SWITCH_OK_WITH_LOCALE,
        SWITCH_MISSING_USER_ID
    }

    private void verifyICarOnUserLifecycleEventCalled(int eventType, long minTimestamp,
            @UserIdInt int fromId, @UserIdInt int toId) throws Exception {
        verify(mCarServiceProxy).sendUserLifecycleEvent(eq(eventType),
                isTargetUser(fromId), isTargetUser(toId));
    }

    private static TargetUser isTargetUser(@UserIdInt int userId) {
        return argThat((user) -> {
            return user == null || user.getUserIdentifier() == userId;
        });
    }


    private void verifyICarOnUserLifecycleEventNeverCalled() throws Exception {
        verify(mCarServiceProxy, never()).sendUserLifecycleEvent(anyInt(), any(), any());
    }

    private void verifyPreCreatedUsers() throws Exception {
        verify(mCarServiceProxy).preCreateUsers();
    }

    private void verifyInitBootUser() throws Exception {
        verify(mCarServiceProxy).initBootUser();
    }

    /**
     * Asserts that no exception was thrown when answering to a mocked {@code ICar} binder call.
     * <p>
     * This method should be called before asserting the expected results of a test, so it makes
     * clear why the test failed when the call was not made as expected.
     */
    private void assertNoICarCallExceptions() throws Exception {
        if (mBinderCallException != null)
            throw mBinderCallException;

    }

    // TODO(b/162241237): Use mock instead of fake if possible.
    private final class FakeICarSystemServerClient extends ICarSystemServerClient.Stub {
        @Override
        public void onUserLifecycleEvent(int eventType, long timestamp, @UserIdInt int fromId,
                @UserIdInt int toId) throws RemoteException {
        }

        @Override
        public void initBootUser() throws RemoteException {
        }

        @Override
        public void preCreateUsers() throws RemoteException {
        }

    }
}
