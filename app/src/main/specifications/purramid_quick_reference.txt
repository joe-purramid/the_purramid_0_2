# The Purramid Quick Reference

## App-Intent Types
- Services: Clock, Screen Mask, Spotlight, Timers, Traffic Light
- Activities: Randomizers, About
- Launcher: MainActivity

## Key Patterns
- Instance IDs: Integer 1-4 per window
- Persistence: Room for instances, SharedPrefs for app-level
- DI: Hilt for all components
- Multi-window: InstanceManager coordinates

## Review Process
1. Check against Technical Architecture
2. Verify Universal Requirements
3. Compare with previous decisions
4. Test instance tracking