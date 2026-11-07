#!/usr/bin/env python3
"""Create phone-owned synthetic actors in a NEW account on the authorized P5 backend.
The supplied backend/container/ports and the four shared fixtures are never changed.
Private fixtures stay 0600 in caller-owned scratch; no credentials enter stdout.
"""
import argparse, base64, hashlib, importlib.util, json, os, secrets, sys, time, uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['create'])
    parser.add_argument('--environment', required=True, type=Path)
    parser.add_argument('--scratch', required=True, type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    environment = args.environment.resolve()
    original = json.loads((environment / '.owner.json').read_text())
    if original['backendPort'] != 18972 or original['postgresPort'] != 18973:
        raise RuntimeError('authorized P5 loopback environment required')
    args.scratch.mkdir(parents=True, exist_ok=True, mode=0o700)
    sys.path.insert(0, str(environment))
    spec = importlib.util.spec_from_file_location('wrss_p5_local_manage', environment / 'manage.py')
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    m.local_docker()
    m.owned_container()
    m.ROOT = args.scratch.resolve()
    if (m.ROOT / '.owner.json').exists():
        raise RuntimeError('scratch already contains a synthetic account; choose a new private directory')
    user = str(uuid.uuid4())
    scope = {'repoId': m.uid(), 'libraryEpoch': '1', 'contentGeneration': '1'}
    o = dict(original, accountUserId=user, accountId=user.replace('-', ''), scope=scope,
             legacyLibraryEpoch=str(uuid.uuid4()), legacyContentGeneration=str(uuid.uuid4()),
             expiresAtMillis=int((time.time() + 86400) * 1000), phoneP5Owned=True)
    m.write('.owner.json', o)
    content, id_key, recovery = (secrets.token_bytes(32) for _ in range(3))
    for platform in ['watch', 'phone', 'mini', 'cli']:
        legacy = m.ec.generate_private_key(m.ec.SECP256R1())
        signing = m.ec.generate_private_key(m.ec.SECP256R1())
        device_id, sync_id = 'synthetic-p5-phone-' + platform + '-' + m.uid(), m.uid()
        token = ('wrw_' if platform == 'watch' else 'wrp_') + secrets.token_hex(32)
        access = None
        headers = {'Authorization': 'Bearer ' + token, 'x-watchrss-cloud-device-id': device_id,
                   'x-watchrss-library-epoch': o['legacyLibraryEpoch'], 'x-watchrss-content-generation': o['legacyContentGeneration']}
        if platform != 'watch':
            prefix, header = {'phone': ('wrd_', 'x-watchrss-device-authorization'), 'mini': ('wrm_', 'x-watchrss-mini-authorization'), 'cli': ('wrc_', 'x-watchrss-cli-authorization')}[platform]
            access = prefix + secrets.token_hex(32)
            headers[header] = 'Bearer ' + access
        ephemeral = m.ec.generate_private_key(m.ec.SECP256R1())
        nonce = secrets.token_bytes(12)
        shared = ephemeral.exchange(m.ec.ECDH(), legacy.public_key())
        wrapped_key = m.hkdf(shared, hashlib.sha256(user.encode()).digest(), ('watchrss/device-envelope/v1/' + device_id).encode())
        cipher = m.AESGCM(wrapped_key).encrypt(nonce, content, ('watchrss-account-key:' + user + ':1:' + device_id).encode())
        envelope = {'recipientType': 'device', 'recipientDeviceId': device_id, 'keyVersion': 1,
                    'algorithm': 'P-256+HKDF-SHA256+A256GCM', 'wrappedKeyBase64': m.std(cipher),
                    'nonceBase64': m.std(nonce), 'ephemeralPublicKeySpki': m.std(m.spki(ephemeral)), 'createdByDeviceId': 'synthetic-mini-bootstrap'}
        body = {'version': 2, 'scope': scope, 'deviceId': sync_id, 'keyId': m.uid(), 'publicKey': m.b64(m.point(signing)), 'authorizedAtMillis': int(time.time()*1000), 'revocationRevision': '0'}
        proof = m.b64(m.mac(id_key, m.lp([b'wrss.sync.v2/signing-key-bind', m.j(body)])))
        registration = {'body': body, 'bindingProof': proof, 'possessionSignature': m.sign(signing, m.lp([b'wrss.sync.v2/signing-key-possession', m.j(body), proof.encode()]))}
        fixture = {'version': 1, 'syntheticOnly': True, 'platform': platform, 'baseUrl': 'http://127.0.0.1:18972',
                   'userId': user, 'accountId': o['accountId'], 'legacyDeviceId': device_id, 'syncDeviceId': sync_id, 'deviceId': sync_id,
                   'scope': scope, 'legacyLibraryEpoch': o['legacyLibraryEpoch'], 'legacyContentGeneration': o['legacyContentGeneration'],
                   'expiresAtMillis': o['expiresAtMillis'], 'headers': headers, 'auth': {'accessToken': token, 'platformAccessToken': access},
                   'crypto': {'idKeyHex': id_key.hex(), 'contentKeyHex': content.hex(), 'recoveryEntropyHex': recovery.hex(), 'wrappingKeyVersion': 1},
                   'legacyDeviceKey': {'privateKeyPkcs8Pem': m.private(legacy), 'publicKeySpkiBase64': m.std(m.spki(legacy))},
                   'signingKey': {'keyId': body['keyId'], 'privateKeyHex': signing.private_numbers().private_value.to_bytes(32, 'big').hex()},
                   'keyRegistrationRequest': registration, 'legacyEnvelope': envelope,
                   'servicePin': {'algorithm': 'ES256', 'receiptKeyId': o['receiptKeyId'], 'receiptSigningPublicKey': o['receiptSigningPublicKey']}}
        fixture['legacyAttestation'] = m.legacy_attestation(fixture)
        m.write(platform + '.json', fixture)
    mini = m.read('mini.json')
    # Descriptor author is the independent signing key, never the legacy ECDH key.
    creator = m.ec.derive_private_key(int(mini['signingKey']['privateKeyHex'], 16), m.ec.SECP256R1())
    body = {'version': 2, 'accountId': o['accountId'], 'scope': scope, 'creationId': m.uid(), 'creatorDeviceId': mini['deviceId'],
            'creatorPlatform': 'mini', 'wrappingKeyVersion': 1, 'creatorPublicKey': m.point(creator).hex(),
            'idKeyCommitment': m.digest(m.lp([b'wrss.sync.v2/id-key-commitment', id_key]))}
    proof = m.b64(m.mac(content, m.lp([b'wrss.sync.v2/repo-bootstrap-auth', m.j(body)])))
    descriptor = {'body': body, 'proof': proof, 'signature': m.sign(creator, m.lp([b'wrss.sync.v2/repo-bootstrap-sign', m.j({'body': body, 'proof': proof})]))}
    descriptor_oid = 'repo-descriptor:sha256:' + m.digest(m.j(descriptor))
    salt, nonce = secrets.token_bytes(32), secrets.token_bytes(12)
    header = {'version': 2, 'domain': 'wrss.sync.v2/id-key-recovery', 'accountId': o['accountId'], 'scope': scope,
              'descriptorOid': descriptor_oid, 'wrappingKeyVersion': 1, 'salt': m.b64(salt), 'nonce': m.b64(nonce)}
    aad = m.j(header)
    key = m.hkdf(content, salt, m.lp([header['domain'].encode(), hashlib.sha256(aad).digest()]))
    capsule = {'header': header, 'ciphertext': m.b64(m.AESGCM(key).encrypt(nonce, m.j({'idKeyHex': id_key.hex()}), aad))}
    m.write('repo-init.json', {'descriptor': descriptor, 'recoveryCapsule': capsule})
    m.seed()
    m.init_repo()
    m.sql('update public.sync2_flags set history_restore=true,gc=false where account_id=' + m.quote(user) + ';')
    print('phone-owned synthetic account and four actors: passed')


if __name__ == '__main__':
    main()
