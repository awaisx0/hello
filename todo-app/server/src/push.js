const webpush = require('web-push');

const publicKey = process.env.VAPID_PUBLIC_KEY || '';
const privateKey = process.env.VAPID_PRIVATE_KEY || '';
const subject = process.env.VAPID_SUBJECT || 'mailto:example@example.com';

const configured = Boolean(publicKey && privateKey);
if (configured) {
  webpush.setVapidDetails(subject, publicKey, privateKey);
}

module.exports = { webpush, configured, publicKey };
