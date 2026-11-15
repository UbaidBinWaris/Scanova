// Fills the download page from the same file the in-app updater reads.
// Served under a CSP without 'unsafe-inline': this file is the only script.
(function () {
  'use strict';

  var FEED = '/downloads/android/latest.json';
  var $ = function (id) {
    return document.getElementById(id);
  };

  function formatSize(bytes) {
    if (typeof bytes !== 'number' || !isFinite(bytes)) return '—';
    return (bytes / 1048576).toFixed(1) + ' MB';
  }

  function formatDate(iso) {
    var date = new Date(iso);
    if (isNaN(date.getTime())) return '—';
    return date.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
  }

  function isSafeFileName(name) {
    return typeof name === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$/.test(name);
  }

  function unavailable(message) {
    var link = $('download');
    link.setAttribute('aria-disabled', 'true');
    link.removeAttribute('href');
    link.classList.add('is-disabled');
    $('build-line').textContent = message;
  }

  // The site lives at scanova.<domain>; the app pairs with api.scanova.<domain>.
  var host = window.location.hostname;
  if (host && host.indexOf('api.') !== 0 && host.indexOf('.') > 0) {
    $('server-address').textContent = 'https://api.' + host;
  }

  fetch(FEED, { cache: 'no-cache', credentials: 'omit' })
    .then(function (response) {
      if (response.status === 404) throw new Error('none');
      if (!response.ok) throw new Error('http');
      return response.json();
    })
    .then(function (release) {
      if (!isSafeFileName(release.file)) throw new Error('bad');
      $('download').setAttribute('href', '/downloads/android/' + release.file);
      $('build-line').textContent =
        'Version ' + release.versionName + ' · ' + formatSize(release.sizeBytes) + ' · Android 8.0 or newer';
      $('version').textContent = release.versionName + ' (' + release.versionCode + ')';
      $('published').textContent = formatDate(release.publishedAt);
      $('size').textContent = formatSize(release.sizeBytes);
      $('sha256').textContent = release.sha256 || '—';
      $('cert').textContent = release.signingCertSha256 || '—';
      var notes = Array.isArray(release.notes) ? release.notes.slice(0, 12) : [];
      if (notes.length > 0) {
        var list = $('notes');
        notes.forEach(function (note) {
          var item = document.createElement('li');
          item.textContent = String(note);
          list.appendChild(item);
        });
        $('notes-wrap').hidden = false;
      }
    })
    .catch(function (error) {
      unavailable(
        error && error.message === 'none'
          ? 'No Android build has been published yet. Check back soon.'
          : 'The build details could not be loaded. Reload the page to try again.',
      );
    });
})();
