package org.robolectric.internal;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

import com.android.ide.common.rendering.api.HardwareConfig;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.ResourceRepository;
import com.android.ide.common.resources.ResourceResolver;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.io.FolderWrapper;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.impl.RenderAction;
import com.android.resources.Density;
import com.android.resources.Keyboard;
import com.android.resources.KeyboardState;
import com.android.resources.Navigation;
import com.android.resources.NavigationState;
import com.android.resources.ScreenOrientation;
import com.android.resources.ScreenRatio;
import com.android.resources.ScreenSize;
import com.android.resources.TouchScreen;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.ShadowsAdapter;
import org.robolectric.TestLifecycle;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.ProjectCallback;
import org.robolectric.fakes.RenderService;
import org.robolectric.fakes.RenderServiceFactory;
import org.robolectric.internal.fakes.RoboInstrumentation;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.ResBundle;
import org.robolectric.res.ResourceLoader;
import org.robolectric.res.builder.DefaultPackageManager;
import org.robolectric.shadows.ShadowAccessibilityEvent;
import org.robolectric.shadows.ShadowBridgeContext;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import org.xmlpull.v1.XmlPullParserException;
import org.robolectric.util.Scheduler;


import java.io.File;
import java.io.FileNotFoundException;
import java.lang.reflect.Method;
import java.security.Security;

import static org.robolectric.util.ReflectionHelpers.ClassParameter;

public class ParallelUniverse implements ParallelUniverseInterface {
  private static final String DEFAULT_PACKAGE_NAME = "org.robolectric.default";
  private static RenderService renderService;
  private static ResourceResolver renderResources;
  private final RobolectricTestRunner robolectricTestRunner;
  private final ShadowsAdapter shadowsAdapter = Robolectric.getShadowsAdapter();

  private boolean loggingInitialized = false;
  private SdkConfig sdkConfig;

  public ParallelUniverse(RobolectricTestRunner robolectricTestRunner) {
    this.robolectricTestRunner = robolectricTestRunner;
  }

  @Override
  public void resetStaticState(Config config) {
    RuntimeEnvironment.setMainThread(Thread.currentThread());
    Robolectric.reset();

    if (!loggingInitialized) {
      shadowsAdapter.setupLogging();
      loggingInitialized = true;
    }
  }

  /*
   * If the Config already has a version qualifier, do nothing. Otherwise, add a version
   * qualifier for the target api level (which comes from the manifest or Config.emulateSdk()).
   */
  private String addVersionQualifierToQualifiers(String qualifiers) {
    int versionQualifierApiLevel = ResBundle.getVersionQualifierApiLevel(qualifiers);
    if (versionQualifierApiLevel == -1) {
      if (qualifiers.length() > 0) {
        qualifiers += "-";
      }
      qualifiers += "v" + sdkConfig.getApiLevel();
    }
    return qualifiers;
  }

  @Override
  public void setUpApplicationState(Method method, TestLifecycle testLifecycle, ResourceLoader systemResourceLoader, AndroidManifest appManifest, Config config) {
    RuntimeEnvironment.application = null;
    RuntimeEnvironment.setMasterScheduler(new Scheduler());
    RuntimeEnvironment.setMainThread(Thread.currentThread());
    RuntimeEnvironment.setRobolectricPackageManager(new DefaultPackageManager(shadowsAdapter));
    RuntimeEnvironment.getRobolectricPackageManager().addPackage(DEFAULT_PACKAGE_NAME);
    ResourceLoader resourceLoader;
    if (appManifest != null) {
      resourceLoader = robolectricTestRunner.getAppResourceLoader(sdkConfig, systemResourceLoader, appManifest);
      RuntimeEnvironment.getRobolectricPackageManager().addManifest(appManifest, resourceLoader);
    } else {
      resourceLoader = systemResourceLoader;
    }

    if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
      Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }

    shadowsAdapter.setSystemResources(systemResourceLoader);
    String qualifiers = addVersionQualifierToQualifiers(config.qualifiers());
    Resources systemResources = Resources.getSystem();
    Configuration configuration = systemResources.getConfiguration();
    shadowsAdapter.overrideQualifiers(configuration, qualifiers);
    systemResources.updateConfiguration(configuration, systemResources.getDisplayMetrics());
    RuntimeEnvironment.setQualifiers(qualifiers);
    RuntimeEnvironment.setApiLevel(sdkConfig.getApiLevel());
    RuntimeEnvironment.setRendering(sdkConfig.isRendering());

    Class<?> contextImplClass = ReflectionHelpers.loadClass(getClass().getClassLoader(), shadowsAdapter.getShadowContextImplClassName());

    Class<?> activityThreadClass = ReflectionHelpers.loadClass(getClass().getClassLoader(), shadowsAdapter.getShadowActivityThreadClassName());
    // Looper needs to be prepared before the activity thread is created
//    if (Looper.getMainLooper() == null) {
    if (Looper.myLooper() == null) {
      Looper.prepareMainLooper();
    }
    ShadowLooper.getShadowMainLooper().resetScheduler();
    Object activityThread = ReflectionHelpers.newInstance(activityThreadClass);
    RuntimeEnvironment.setActivityThread(activityThread);

    ReflectionHelpers.setField(activityThread, "mInstrumentation", new RoboInstrumentation());
    ReflectionHelpers.setField(activityThread, "mCompatConfiguration", configuration);

    Context systemContextImpl = ReflectionHelpers.callStaticMethod(contextImplClass, "createSystemContext", ClassParameter.from(activityThreadClass, activityThread));

    final Application application = (Application) testLifecycle.createApplication(method, appManifest, config);
    if (application != null) {
      String packageName = appManifest != null ? appManifest.getPackageName() : null;
      if (packageName == null) packageName = DEFAULT_PACKAGE_NAME;

      ApplicationInfo applicationInfo;
      try {
        applicationInfo = RuntimeEnvironment.getPackageManager().getApplicationInfo(packageName, 0);
      } catch (PackageManager.NameNotFoundException e) {
        throw new RuntimeException(e);
      }

      Class<?> compatibilityInfoClass = ReflectionHelpers.loadClass(getClass().getClassLoader(), "android.content.res.CompatibilityInfo");

      Object loadedApk = ReflectionHelpers.callInstanceMethod(activityThread, "getPackageInfo",
          ClassParameter.from(ApplicationInfo.class, applicationInfo),
          ClassParameter.from(compatibilityInfoClass, null),
          ClassParameter.from(int.class, Context.CONTEXT_INCLUDE_CODE));

      shadowsAdapter.bind(application, appManifest, resourceLoader);
      if (appManifest == null) {
        // todo: make this cleaner...
        shadowsAdapter.setPackageName(application, applicationInfo.packageName);
      }
      Resources appResources = application.getResources();
      ReflectionHelpers.setField(loadedApk, "mResources", appResources);
      try {
        Context contextImpl = systemContextImpl.createPackageContext(applicationInfo.packageName, Context.CONTEXT_INCLUDE_CODE);
        ReflectionHelpers.setField(activityThreadClass, activityThread, "mInitialApplication", application);
        ReflectionHelpers.callInstanceMethod(Application.class, application, "attach", ClassParameter.from(Context.class, contextImpl));
      } catch (PackageManager.NameNotFoundException e) {
        throw new RuntimeException(e);
      }

      appResources.updateConfiguration(configuration, appResources.getDisplayMetrics());
      shadowsAdapter.setAssetsQualifiers(appResources.getAssets(), qualifiers);

      if (sdkConfig.isRendering()) {
        final String SDK = System.getenv("ANDROID_HOME");
        File f = new File(SDK + "/platforms/android-" + sdkConfig.getApiLevel());
        RenderServiceFactory factory = RenderServiceFactory.create(f);
        String appResDir = "";
        if (appManifest != null) {
          appResDir = appManifest.getResDirectory().getPath();
          RuntimeEnvironment.setResourceDir(appResDir);
        }
        ResourceRepository projectRes =
            new ResourceRepository(new FolderWrapper(appResDir), false/*isFramework*/) {
              @Override
              protected ResourceItem createResourceItem(String name) {
                return new ResourceItem(name);
              }
            };
        projectRes.loadResources();
        // create the rendering config
        FolderConfiguration folderConfig =
            RenderServiceFactory.createConfig(1280, 800, ScreenSize.XLARGE, ScreenRatio.LONG,
                ScreenOrientation.PORTRAIT, Density.MEDIUM, TouchScreen.FINGER, KeyboardState.SOFT,
                Keyboard.QWERTY, NavigationState.EXPOSED, Navigation.NONAV, 21/*api level*/);
        // create the resource resolver once for the given config.
        String themeKey = null;
        boolean isProjectTheme;
        if (appManifest.getThemeRef() != null && appManifest.getThemeRef().length() > 1) {
          String themeRef = appManifest.getThemeRef().substring(1); //Remove '@'
          if (themeRef.contains("android")) {
            // Framework theme
            isProjectTheme = false;
          } else {
            // Project theme
            isProjectTheme = true;
          }
          int indexOfSlash = themeRef.indexOf('/');
          if (indexOfSlash >= 0) {
            themeKey = themeRef.substring(indexOfSlash + 1);
          } else {
            themeKey = "Theme";
            isProjectTheme = false;
          }
        } else {
          isProjectTheme = false;
          themeKey = "Theme";
        }

        // create the render service
        if (renderService == null) {
          renderResources = factory.createResourceResolver(folderConfig, projectRes, themeKey, isProjectTheme);
          renderService = factory.createService(renderResources, folderConfig, new ProjectCallback());
        }

        try {
          initBridgeResources("activity_main");
          } catch (FileNotFoundException e) {
          e.printStackTrace();
        } catch (XmlPullParserException e) {
          e.printStackTrace();
        }
      }
      RuntimeEnvironment.application = application;
      application.onCreate();
    } else if (sdkConfig.isRendering() && appManifest == null) {
      throw new RuntimeException("Please provide a manifest for rendering");
    }
  }

  public static void initBridgeResources(String layoutName) throws FileNotFoundException, XmlPullParserException {
    SessionParams params;
    params = renderService.getParamsFromFile(layoutName);
    HardwareConfig hardwareConfig = params.getHardwareConfig();

    DisplayMetrics metrics = new DisplayMetrics();
    metrics.densityDpi = (metrics.noncompatDensityDpi = hardwareConfig.getDensity().getDpiValue());

    metrics.density = (metrics.noncompatDensity = metrics.densityDpi / 160.0F);

    metrics.scaledDensity = (metrics.noncompatScaledDensity = metrics.density);

    metrics.widthPixels = (metrics.noncompatWidthPixels = hardwareConfig.getScreenWidth());
    metrics.heightPixels = (metrics.noncompatHeightPixels = hardwareConfig.getScreenHeight());
    metrics.xdpi = (metrics.noncompatXdpi = hardwareConfig.getXdpi());
    metrics.ydpi = (metrics.noncompatYdpi = hardwareConfig.getYdpi());
    BridgeContext bridgeContext =
        ShadowBridgeContext.obtain(params.getProjectKey(), metrics, renderResources, null, params.getProjectCallback(),
            getConfiguration(params), params.getTargetSdkVersion(), params.isRtlSupported());
    bridgeContext.initResources();
    ReflectionHelpers.setStaticField(RenderAction.class, "sCurrentContext", bridgeContext);
  }

  private static Configuration getConfiguration(SessionParams params)
  {
    Configuration config = new Configuration();

    HardwareConfig hardwareConfig = params.getHardwareConfig();

    ScreenSize screenSize = hardwareConfig.getScreenSize();
    if (screenSize != null) {
      switch (screenSize)
      {
      case SMALL:
        config.screenLayout |= 0x1;
        break;
      case NORMAL:
        config.screenLayout |= 0x2;
        break;
      case LARGE:
        config.screenLayout |= 0x3;
        break;
      case XLARGE:
        config.screenLayout |= 0x4;
      }
    }
    Density density = hardwareConfig.getDensity();
    if (density == null) {
      density = Density.MEDIUM;
    }
    config.screenWidthDp = (hardwareConfig.getScreenWidth() / density.getDpiValue());
    config.screenHeightDp = (hardwareConfig.getScreenHeight() / density.getDpiValue());
    if (config.screenHeightDp < config.screenWidthDp) {
      config.smallestScreenWidthDp = config.screenHeightDp;
    } else {
      config.smallestScreenWidthDp = config.screenWidthDp;
    }
    config.densityDpi = density.getDpiValue();

    config.compatScreenWidthDp = config.screenWidthDp;
    config.compatScreenHeightDp = config.screenHeightDp;

    ScreenOrientation orientation = hardwareConfig.getOrientation();
    if (orientation != null) {
      switch (orientation)
      {
      case PORTRAIT:
        config.orientation = 1;
        break;
      case LANDSCAPE:
        config.orientation = 2;
        break;
      case SQUARE:
        config.orientation = 3;
      }
    } else {
      config.orientation = 0;
    }
    return config;
  }

  @Override
  public Thread getMainThread() {
    return RuntimeEnvironment.getMainThread();
  }

  @Override
  public void setMainThread(Thread newMainThread) {
    RuntimeEnvironment.setMainThread(newMainThread);
  }

  @Override
  public void tearDownApplication() {
    if (RuntimeEnvironment.application != null) {
      RuntimeEnvironment.application.onTerminate();
    }
  }

  @Override
  public Object getCurrentApplication() {
    return RuntimeEnvironment.application;
  }

  @Override
  public void setSdkConfig(SdkConfig sdkConfig) {
    this.sdkConfig = sdkConfig;
  }
}
