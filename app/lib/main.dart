import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:fakelinux/theme/droid_theme.dart';
import 'package:fakelinux/state/app_state.dart';
import 'package:fakelinux/services/platform_bridge.dart';
import 'package:fakelinux/screens/welcome_screen.dart';
import 'package:fakelinux/screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  FakeLinuxPlatform.init();

  runApp(
    ChangeNotifierProvider(
      create: (_) => AppState(),
      child: const FakeLinuxApp(),
    ),
  );
}

class FakeLinuxApp extends StatefulWidget {
  const FakeLinuxApp({super.key});

  @override
  State<FakeLinuxApp> createState() => _FakeLinuxAppState();
}

class _FakeLinuxAppState extends State<FakeLinuxApp> {
  @override
  void initState() {
    super.initState();
    SystemChrome.setEnabledSystemUIMode(
      SystemUiMode.manual,
      overlays: SystemUiOverlay.values,
    );
    // Initialize platform bridge and load state
    WidgetsBinding.instance.addPostFrameCallback((_) {
      context.read<AppState>().initialize();
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = context.watch<AppState>();
    final isDark = state.isDarkMode;

    final overlayStyle = SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: isDark ? Brightness.light : Brightness.dark,
      statusBarBrightness: isDark ? Brightness.dark : Brightness.light,
      systemNavigationBarColor: Colors.transparent,
      systemNavigationBarIconBrightness: isDark ? Brightness.light : Brightness.dark,
    );

    SystemChrome.setSystemUIOverlayStyle(overlayStyle);

    return MaterialApp(
      title: 'FakeLinux',
      debugShowCheckedModeBanner: false,
      theme: DroidTheme.lightThemeData,
      darkTheme: DroidTheme.darkThemeData,
      themeMode: state.themeMode,
      builder: (context, child) {
        return AnnotatedRegion<SystemUiOverlayStyle>(
          value: overlayStyle,
          child: child ?? const SizedBox.shrink(),
        );
      },
      home: state.isSetupComplete ? const HomeScreen() : const WelcomeScreen(),
    );
  }
}
