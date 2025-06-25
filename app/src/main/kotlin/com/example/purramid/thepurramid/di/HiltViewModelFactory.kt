package com.example.purramid.thepurramid.di

import android.os.Bundle
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.savedstate.SavedStateRegistryOwner
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import javax.inject.Inject

/**
 * A custom ViewModel factory that bridges Hilt's dependency injection with the manual creation
 * of ViewModels that require a SavedStateHandle.
 *
 * This factory is used in places like a Service where direct @AndroidEntryPoint ViewModel injection
 * isn't as straightforward as in an Activity or Fragment.
 *
 * @param owner The SavedStateRegistryOwner (e.g., the service itself) needed to create the SavedStateHandle.
 * @param defaultArgs An optional Bundle of default arguments to pass to the SavedStateHandle.
 * @param delegate The Hilt-provided ViewModelProvider.Factory that knows how to perform the actual
 * dependency injection for the ViewModel.
 */
class HiltViewModelFactory(
    owner: SavedStateRegistryOwner,
    defaultArgs: Bundle?,
    private val delegate: ViewModelProvider.Factory
) : AbstractSavedStateViewModelFactory(owner, defaultArgs) {

    /**
     * Creates a new instance of the given `ViewModel` class.
     *
     * This method is responsible for creating the ViewModel. It leverages the Hilt `delegate` factory
     * to handle the actual creation and injection of dependencies into the ViewModel.
     * The `SavedStateHandle` created by this `AbstractSavedStateViewModelFactory` is automatically
     * made available for injection into the ViewModel by Hilt.
     *
     * @param key An optional key to distinguish between different ViewModels of the same type.
     * @param modelClass The class of the ViewModel to create.
     * @param handle The SavedStateHandle to be associated with the new ViewModel.
     * @return A newly created ViewModel.
     */
    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {
        // The Hilt-provided delegate factory will know how to create the requested ViewModel
        // and automatically inject its dependencies, including the SavedStateHandle.
        // We don't need to manually pass the 'handle' here because Hilt's magic handles it
        // as long as the ViewModel is annotated with @HiltViewModel.
        return delegate.create(modelClass, handle)
    }
}
