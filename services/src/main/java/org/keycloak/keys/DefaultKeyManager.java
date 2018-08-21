/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.keys;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.crypto.Algorithm;
import org.keycloak.crypto.KeyUse;
import org.keycloak.crypto.KeyWrapper;
import org.keycloak.models.KeyManager;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderFactory;

import javax.crypto.SecretKey;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.*;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class DefaultKeyManager implements KeyManager {

    private static final Logger logger = Logger.getLogger(DefaultKeyManager.class);

    private final KeycloakSession session;
    private final Map<String, List<KeyProvider>> providersMap = new HashMap<>();

    public DefaultKeyManager(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public KeyWrapper getActiveKey(RealmModel realm, KeyUse use, String algorithm) {
        for (KeyProvider p : getProviders(realm)) {
            for (KeyWrapper key : p .getKeys()) {
                if (key.getStatus().isActive() && matches(key, use, algorithm)) {
                    if (logger.isTraceEnabled()) {
                        logger.tracev("Active key found: realm={0} kid={1} algorithm={2}", realm.getName(), key.getKid(), algorithm);
                    }

                    return key;
                }
            }
        }
        throw new RuntimeException("Failed to find key: realm=" + realm.getName() + " algorithm=" + algorithm);
    }

    @Override
    public KeyWrapper getKey(RealmModel realm, String kid, KeyUse use, String algorithm) {
        if (kid == null) {
            logger.warnv("kid is null, can't find public key", realm.getName(), kid);
            return null;
        }

        for (KeyProvider p : getProviders(realm)) {
            for (KeyWrapper key : p.getKeys()) {
                if (key.getKid().equals(kid) && key.getStatus().isEnabled() && matches(key, use, algorithm)) {
                    if (logger.isTraceEnabled()) {
                        logger.tracev("Active key realm={0} kid={1} algorithm={2}", realm.getName(), key.getKid(), algorithm);
                    }

                    return key;
                }
            }
        }

        if (logger.isTraceEnabled()) {
            logger.tracev("Failed to find public key realm={0} kid={1} algorithm={2}", realm.getName(), kid, algorithm);
        }

        return null;
    }

    @Override
    public List<KeyWrapper> getKeys(RealmModel realm, KeyUse use, String algorithm) {
        List<KeyWrapper> keys = new LinkedList<>();
        for (KeyProvider p : getProviders(realm)) {
            for (KeyWrapper key : p .getKeys()) {
                if (key.getStatus().isEnabled() && matches(key, use, algorithm)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    @Override
    public List<KeyWrapper> getKeys(RealmModel realm) {
        List<KeyWrapper> keys = new LinkedList<>();
        for (KeyProvider p : getProviders(realm)) {
            for (KeyWrapper key : p .getKeys()) {
                keys.add(key);
            }
        }
        return keys;
    }

    @Override
    @Deprecated
    public ActiveRsaKey getActiveRsaKey(RealmModel realm) {
        KeyWrapper key = getActiveKey(realm, KeyUse.SIG, Algorithm.RS256);
        return new ActiveRsaKey(key.getKid(), (PrivateKey) key.getSignKey(), (PublicKey) key.getVerifyKey(), key.getCertificate());
    }

    @Override
    @Deprecated
    public ActiveHmacKey getActiveHmacKey(RealmModel realm) {
        KeyWrapper key = getActiveKey(realm, KeyUse.SIG, Algorithm.HS256);
        return new ActiveHmacKey(key.getKid(), key.getSecretKey());
    }

    @Override
    @Deprecated
    public ActiveAesKey getActiveAesKey(RealmModel realm) {
        KeyWrapper key = getActiveKey(realm, KeyUse.ENC, Algorithm.AES);
        return new ActiveAesKey(key.getKid(), key.getSecretKey());
    }

    @Override
    @Deprecated
    public PublicKey getRsaPublicKey(RealmModel realm, String kid) {
        KeyWrapper key = getKey(realm, kid, KeyUse.SIG, Algorithm.RS256);
        return key != null ? (PublicKey) key.getVerifyKey() : null;
    }

    @Override
    @Deprecated
    public Certificate getRsaCertificate(RealmModel realm, String kid) {
        KeyWrapper key = getKey(realm, kid, KeyUse.SIG, Algorithm.RS256);
        return key != null ? key.getCertificate() : null;
    }

    @Override
    @Deprecated
    public SecretKey getHmacSecretKey(RealmModel realm, String kid) {
        KeyWrapper key = getKey(realm, kid, KeyUse.SIG, Algorithm.HS256);
        return key != null ? key.getSecretKey() : null;
    }

    @Override
    @Deprecated
    public SecretKey getAesSecretKey(RealmModel realm, String kid) {
        KeyWrapper key = getKey(realm, kid, KeyUse.ENC, Algorithm.AES);
        return key.getSecretKey();
    }

    @Override
    @Deprecated
    public List<RsaKeyMetadata> getRsaKeys(RealmModel realm) {
        List<RsaKeyMetadata> keys = new LinkedList<>();
        for (KeyWrapper key : getKeys(realm, KeyUse.SIG, Algorithm.RS256)) {
            RsaKeyMetadata m = new RsaKeyMetadata();
            m.setCertificate(key.getCertificate());
            m.setPublicKey((PublicKey) key.getVerifyKey());
            m.setKid(key.getKid());
            m.setProviderId(key.getProviderId());
            m.setProviderPriority(key.getProviderPriority());
            m.setStatus(key.getStatus());

            keys.add(m);
        }
        return keys;
    }

    @Override
    public List<SecretKeyMetadata> getHmacKeys(RealmModel realm) {
        List<SecretKeyMetadata> keys = new LinkedList<>();
        for (KeyWrapper key : getKeys(realm, KeyUse.SIG, Algorithm.HS256)) {
            SecretKeyMetadata m = new SecretKeyMetadata();
            m.setKid(key.getKid());
            m.setProviderId(key.getProviderId());
            m.setProviderPriority(key.getProviderPriority());
            m.setStatus(key.getStatus());

            keys.add(m);
        }
        return keys;
    }

    @Override
    public List<SecretKeyMetadata> getAesKeys(RealmModel realm) {
        List<SecretKeyMetadata> keys = new LinkedList<>();
        for (KeyWrapper key : getKeys(realm, KeyUse.ENC, Algorithm.AES)) {
            SecretKeyMetadata m = new SecretKeyMetadata();
            m.setKid(key.getKid());
            m.setProviderId(key.getProviderId());
            m.setProviderPriority(key.getProviderPriority());
            m.setStatus(key.getStatus());

            keys.add(m);
        }
        return keys;
    }

    private boolean matches(KeyWrapper key, KeyUse use, String algorithm) {
        return use.equals(key.getUse()) && key.getAlgorithms().contains(algorithm);
    }

    private List<KeyProvider> getProviders(RealmModel realm) {
        List<KeyProvider> providers = providersMap.get(realm.getId());
        if (providers == null) {
            providers = new LinkedList<>();

            List<ComponentModel> components = new LinkedList<>(realm.getComponents(realm.getId(), KeyProvider.class.getName()));
            components.sort(new ProviderComparator());

            for (ComponentModel c : components) {
                try {
                    ProviderFactory<KeyProvider> f = session.getKeycloakSessionFactory().getProviderFactory(KeyProvider.class, c.getProviderId());
                    KeyProviderFactory factory = (KeyProviderFactory) f;
                    KeyProvider provider = factory.create(session, c);
                    session.enlistForClose(provider);
                    providers.add(provider);
                } catch (Throwable t) {
                    logger.errorv(t, "Failed to load provider {0}", c.getId());
                }
            }

            providersMap.put(realm.getId(), providers);

            try {
                getActiveKey(realm, KeyUse.SIG, Algorithm.RS256);
            } catch (RuntimeException e) {
                providers.add(new FailsafeRsaKeyProvider());
            }

            try {
                getActiveKey(realm, KeyUse.SIG, Algorithm.HS256);
            } catch (RuntimeException e) {
                providers.add(new FailsafeHmacKeyProvider());
            }

            try {
                getActiveKey(realm, KeyUse.ENC, Algorithm.AES);
            } catch (RuntimeException e) {
                providers.add(new FailsafeAesKeyProvider());
            }

            try {
                getActiveKey(realm, KeyUse.ENC, Algorithm.ES256);
            } catch (RuntimeException e) {
                providers.add(new FailsafeEcdsaKeyProvider());
            }
        }
        return providers;
    }

    private class ProviderComparator implements Comparator<ComponentModel> {

        @Override
        public int compare(ComponentModel o1, ComponentModel o2) {
            int i = Long.compare(o2.get("priority", 0l), o1.get("priority", 0l));
            return i != 0 ? i : o1.getId().compareTo(o2.getId());
        }

    }
}
