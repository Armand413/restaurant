{{flutter_js}}
{{flutter_build_config}}

// Serve the matching engine with the application, including on a slow/offline network.
const restaurantWebConfig = {
  canvasKitBaseUrl: new URL('canvaskit/', document.baseURI).toString(),
};

_flutter.loader.load({
  config: restaurantWebConfig,
  onEntrypointLoaded: async function (engineInitializer) {
    try {
      const message = document.getElementById('startup-message');
      if (message) message.textContent = 'Préparation de l’affichage…';
      const appRunner = await engineInitializer.initializeEngine(restaurantWebConfig);
      if (message) message.textContent = 'Ouverture de l’application…';
      await appRunner.runApp();
    } catch (error) {
      window.restaurantStartupError();
      console.error('Flutter initialization failed', error);
    }
  },
}).catch(window.restaurantStartupError);
