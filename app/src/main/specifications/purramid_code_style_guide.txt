# The Purramid - Code Style Guide

## Naming Conventions
- Activities: *Activity (e.g., RandomizersHostActivity)
- Services: *Service (e.g., ClockOverlayService)
- ViewModels: *ViewModel
- Repositories: *Repository
- Use lowercase for packages: com.purramid.clock

## State Management
- Use sealed classes for UI states
- Use data classes for preferences
- Immutable data wherever possible

## Coroutines
- Use viewModelScope in ViewModels
- Use lifecycleScope in Activities
- Structured concurrency for Services

## Resource Organization
- Drawables: ic_* for icons, bg_* for backgrounds
- Strings: Organized by app-intent in strings.xml
- Colors: All in PurramidPalette.kt, not colors.xml