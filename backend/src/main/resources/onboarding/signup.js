'use strict';
(() => {
  const nonce = location.hash.slice(1);
  history.replaceState(null, '', location.pathname);
  const status = document.getElementById('status');
  const start = document.getElementById('start');
  const consent = document.getElementById('consent');
  let config, sdkReady = false, codeReady = false, selection, inspecting = false, started = false;
  const message = value => { status.textContent = value; };
  async function request(path, body) {
    const response = await fetch('/connect/whatsapp/' + path, {
      method: body === undefined ? 'GET' : 'POST', cache: 'no-store', credentials: 'omit',
      headers: { 'Content-Type': 'application/json', 'X-Onboarding-Token': nonce },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    let data; try { data = text ? JSON.parse(text) : {}; } catch { data = {}; }
    if (!response.ok) throw new Error(data.message || 'Opération non confirmée. Recommencez depuis les paramètres si le lien a expiré.');
    return data;
  }
  const fail = error => { message(error.message || 'Connexion interrompue. Aucun succès confirmé.'); start.disabled = true; };
  async function inspect() {
    if (!codeReady || !selection || inspecting) return;
    inspecting = true;
    try {
      const identity = await request('inspect', selection);
      document.getElementById('identity').textContent = `${identity.phone} — ${identity.name}\nCompte Meta : ${identity.wabaId}`;
      document.getElementById('review').hidden = false;
      message('Vérifiez le numéro avant de confirmer.');
    } catch (error) { fail(error); }
  }
  window.addEventListener('message', event => {
    if (!started) return;
    let origin; try { origin = new URL(event.origin); } catch { return; }
    if (origin.protocol !== 'https:' || !(origin.hostname === 'facebook.com' || origin.hostname.endsWith('.facebook.com'))) return;
    let payload; try { payload = typeof event.data === 'string' ? JSON.parse(event.data) : event.data; } catch { return; }
    if (!payload || payload.type !== 'WA_EMBEDDED_SIGNUP') return;
    if (payload.event === 'FINISH_WHATSAPP_BUSINESS_APP_ONBOARDING') {
      selection = { event: payload.event, wabaId: payload.data?.waba_id || '', phoneId: payload.data?.phone_number_id || '' };
      void inspect();
    } else if (payload.event === 'CANCEL' || payload.event === 'ERROR') {
      fail(new Error('Parcours Meta annulé ou refusé. Aucun succès confirmé.'));
    } else if (typeof payload.event === 'string' && payload.event.startsWith('FINISH')) {
      fail(new Error('Meta n’a pas confirmé la coexistence. Ne migrez pas votre numéro ; arrêtez ce parcours.'));
    }
  });
  consent.onchange = () => { start.disabled = !consent.checked || !sdkReady || started; };
  start.onclick = () => {
    if (!consent.checked || !sdkReady || started) return;
    started = true; start.disabled = true;
    // Keep FB.login synchronous inside the user gesture to permit the official popup.
    FB.login(response => {
      const code = response.authResponse?.code;
      if (!code) { fail(new Error('Autorisation Meta non terminée.')); return; }
      message('Vérification sécurisée de l’autorisation auprès de Meta…');
      request('exchange', { code }).then(() => { codeReady = true; void inspect(); if (!selection) message('Autorisation reçue. En attente de la confirmation de coexistence par Meta.'); }).catch(fail);
    }, { config_id: config.configId, response_type: 'code', override_default_response_type: true,
      extras: { setup: {}, featureType: 'whatsapp_business_app_onboarding', sessionInfoVersion: '3' } });
  };
  document.getElementById('confirm').onclick = async event => {
    event.target.disabled = true;
    try { const result = await request('confirm', { confirmed: true }); message(result.message); document.getElementById('review').hidden = true; }
    catch (error) { fail(error); }
  };
  request('config').then(value => {
    config = value;
    window.fbAsyncInit = () => { FB.init({ appId: config.appId, version: config.version, xfbml: false, autoLogAppEvents: false }); sdkReady = true; start.disabled = !consent.checked; message('Prêt à ouvrir l’interface officielle Meta.'); };
    const script = document.createElement('script'); script.src = 'https://connect.facebook.net/fr_FR/sdk.js'; script.async = true; script.crossOrigin = 'anonymous';
    script.onerror = () => fail(new Error('Le service Meta est inaccessible. Réessayez plus tard depuis les paramètres.'));
    document.head.appendChild(script);
  }).catch(fail);
})();
