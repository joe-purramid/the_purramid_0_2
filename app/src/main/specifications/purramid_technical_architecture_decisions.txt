# The Purramid - Technical Architecture Decisions
Last Updated: [11JUN25]

## Project Context
- Platform: Android 13+ for Interactive Flat Panels (55"-86")
- Language: Kotlin
- Build System: Gradle with build.gradle.kts and libs.versions.toml

## Architecture Patterns

### Dependency Injection
- **Always use Hilt for:**
  - All Activities (MainActivity, RandomizersHostActivity, AboutActivity)
  - All Services (ClockOverlayService, ScreenMaskService, SpotlightService, TimersService, TrafficLightService)
  - ViewModels requiring injected dependencies
  - Repository classes for data persistence
  - Shared services/utilities across app-intents

- **Use manual injection for:**
  - Simple utility classes with no dependencies
  - View-only classes without business logic
  - Single-use helper functions

### Data Layer
- **Room Database for:**
  - All instance-specific data (window state, user preferences per window)
  - User-created content (lists, sequences, alarms)
  - Complex data structures requiring queries
  - Instance tracking and multi-window management
  
- **Shared Preferences:** 
  - Simple app-level settings (not instance-specific)
  - Permission flags (overlay permission granted)
  - Onboarding completion flags
  - Default values for new instances
  - Managed via qualified providers (@ClockPrefs, @TimersPrefs, etc.)

- **Data Storage Rules:**
  - If it's per-window → Room
  - If it's per-app → SharedPreferences
  - If it's a list/collection → Room
  - If it needs queries → Room
  - If it's a simple flag → SharedPreferences
  
### Architecture Components
- **ViewModel + StateFlow required for:**
  - All Activities with UI state
  - All Services managing overlay windows
  - Complex settings screens
  - Multi-instance coordination

- **Skip ViewModel only for:**
  - AboutActivity (simple static display)
  - Pure utility classes

### Service Architecture
- **Foreground Services for all overlays:**
  - ClockOverlayService
  - ScreenMaskService
  - SpotlightService
  - TimersService
  - TrafficLightService
- **Service requirements:**
  - Android 13+ notification requirements
  - Overlay permission handling
  - State restoration after crash
  - Multi-instance management

### UI Implementation

#### Overlay Windows
- **WindowManager.LayoutParams for:**
  - TYPE_APPLICATION_OVERLAY
  - Appropriate flags for touch handling
  - Pass-through areas: FLAG_NOT_TOUCHABLE
  - Interactive areas: FLAG_NOT_FOCUSABLE

#### Touch Handling
- **Consistent gesture patterns:**
  - Pinch to resize (except Screen Mask/Spotlight special handles)
  - Drag to move
  - Movement threshold: 10dp
  - Exclusive actions (no resize while moving)

#### State Management
- **Icon states:**
  - Default: Original vector drawable
  - Active: Programmatic tint #808080
  - Use ColorStateList, not multiple drawables

#### Animations
- **Consistent durations:**
  - Settings menu: 300ms slide
  - State changes: 200ms fade
  - Use MaterialContainerTransform where appropriate

## Shared Resources
- **Mandatory shared files:**
  - PurramidPalette.kt (color constants)
  - All ic_*.xml drawables
  - Common UI utilities
  - Multi-instance manager

## Instance Management
- **Every app-intent must:**
  - Track instances by UUID
  - Support up to 4 instances (except About: 1, Dice: 7)
  - Save/restore instance state
  - Handle "Add Another" consistently
  - Clear non-last instance preferences on close
  
- **Instance Tracking Pattern:**
  - Integer instanceId (1-4) for window identification
  - UUID for crash recovery and unique identification
  - Centralized InstanceManager service for ID allocation
  
- **Required Entity Fields:**
  - @PrimaryKey val instanceId: Int
  - val uuid: UUID = UUID.randomUUID()
  - val windowX: Int = 0
  - val windowY: Int = 0
  - val windowWidth: Int = -1
  - val windowHeight: Int = -1
  
- **DAO Standardization:**
  - getByInstanceId(instanceId: Int)
  - getAllStates(): List<Entity>
  - deleteByInstanceId(instanceId: Int)
  - getActiveInstanceCount(): Int

## File/Image Handling
- **Consistent limits:**
  - Max image size: 3MB
  - Supported formats: JPG, PNG, BMP, GIF, WEBP
  - Use ActivityResultContracts
  - Store in app-specific storage