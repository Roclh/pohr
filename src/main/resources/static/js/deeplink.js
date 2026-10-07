(function () {
    'use strict';

    const TIMEOUT_MS = 2500;

    // ---------- Modal helpers ----------

    function openModal(id) {
        const el = document.getElementById(id);
        if (el) el.style.display = 'flex';
    }

    function closeModal(id) {
        const el = document.getElementById(id);
        if (el) el.style.display = 'none';
    }

    function closeAllModals() {
        document.querySelectorAll('.modal-overlay').forEach(el => {
            el.style.display = 'none';
        });
    }

    function detectPlatform() {
        return /android/i.test(navigator.userAgent) ? 'android' : 'ios';
    }

    // ---------- Fallback ----------

    let lastAttempt = null;   // { fallbackUrl, ... }

    function showFallback(clientName, fallbackIos, fallbackAndroid) {
        const platform = detectPlatform();
        const fallbackUrl = (platform === 'android' && fallbackAndroid)
            ? fallbackAndroid
            : fallbackIos;

        lastAttempt = { clientName, fallbackIos, fallbackAndroid, platform, fallbackUrl };

        const nameEl = document.getElementById('fallback-client-name');
        const storeBtn = document.getElementById('fallback-store-link');
        const regionBtn = document.getElementById('fallback-region-help');

        if (nameEl) nameEl.textContent = clientName;

        const isIosAppStore = platform === 'ios'
            && fallbackIos && fallbackIos.indexOf('apps.apple.com') !== -1;
        const isGooglePlay = platform === 'android'
            && fallbackAndroid && fallbackAndroid.indexOf('play.google.com') !== -1;

        if (storeBtn) {
            if (!fallbackUrl) {
                storeBtn.style.display = 'none';
            } else {
                storeBtn.style.display = '';
                storeBtn.textContent = isIosAppStore ? 'Открыть App Store'
                    : isGooglePlay ? 'Открыть Google Play'
                        : 'Перейти по ссылке';
            }
        }

        // Инструкция по смене региона — только для iOS + App Store
        if (regionBtn) {
            regionBtn.style.display = isIosAppStore ? '' : 'none';
        }

        openModal('fallback-modal');
    }

    // ---------- Deep-link click ----------

    function attach(panel) {
        panel.addEventListener('click', function (e) {
            e.preventDefault();

            const deepLink = panel.dataset.deeplink;
            const fallbackIos = panel.dataset.fallbackIos || null;
            const fallbackAndroid = panel.dataset.fallbackAndroid || null;
            const clientName = panel.dataset.client;

            if (!deepLink) return;

            const originalHtml = panel.innerHTML;
            panel.innerHTML = '<div class="import-panel-title">Открываем '
                + clientName + '…</div>';
            panel.disabled = true;

            let pageHidden = false;
            const onVisibilityChange = () => {
                if (document.visibilityState === 'hidden') pageHidden = true;
            };
            document.addEventListener('visibilitychange', onVisibilityChange);

            window.location.href = deepLink;

            setTimeout(() => {
                document.removeEventListener('visibilitychange', onVisibilityChange);
                panel.innerHTML = originalHtml;
                panel.disabled = false;

                if (!pageHidden && document.visibilityState !== 'hidden') {
                    showFallback(clientName, fallbackIos, fallbackAndroid);
                }
            }, TIMEOUT_MS);
        });
    }

    // ---------- Wire up ----------

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('.import-panel[data-deeplink]').forEach(attach);

        const storeBtn = document.getElementById('fallback-store-link');
        if (storeBtn) {
            storeBtn.addEventListener('click', () => {
                if (lastAttempt && lastAttempt.fallbackUrl) {
                    window.open(lastAttempt.fallbackUrl, '_blank', 'noopener');
                }
            });
        }

        const regionBtn = document.getElementById('fallback-region-help');
        if (regionBtn) {
            regionBtn.addEventListener('click', () => openModal('region-help-modal'));
        }

        const cancelBtn = document.getElementById('fallback-cancel');
        if (cancelBtn) {
            cancelBtn.addEventListener('click', () => closeModal('fallback-modal'));
        }

        const retryBtn = document.getElementById('region-help-retry');
        if (retryBtn) {
            retryBtn.addEventListener('click', () => {
                closeAllModals();
                if (lastAttempt && lastAttempt.fallbackUrl) {
                    window.open(lastAttempt.fallbackUrl, '_blank', 'noopener');
                }
            });
        }

        const closeRegionBtn = document.getElementById('region-help-close');
        if (closeRegionBtn) {
            closeRegionBtn.addEventListener('click', () => closeModal('region-help-modal'));
        }

        document.querySelectorAll('.modal-overlay').forEach(overlay => {
            overlay.addEventListener('click', (e) => {
                if (e.target === overlay) overlay.style.display = 'none';
            });
        });

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') closeAllModals();
        });
    });
})();