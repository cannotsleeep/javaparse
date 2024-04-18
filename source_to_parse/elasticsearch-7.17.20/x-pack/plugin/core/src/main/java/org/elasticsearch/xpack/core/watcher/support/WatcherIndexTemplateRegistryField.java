/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.core.watcher.support;

public final class WatcherIndexTemplateRegistryField {
    // history (please add a comment why you increased the version here)
    // version 1: initial
    // version 2: added mappings for jira action
    // version 3: include watch status in history
    // version 6: upgrade to ES 6, removal of _status field
    // version 7: add full exception stack traces for better debugging
    // version 8: fix slack attachment property not to be dynamic, causing field type issues
    // version 9: add a user field defining which user executed the watch
    // version 10: add support for foreach path in actions
    // version 11: watch history indices are hidden
    // version 12: templates changed to composable templates
    // version 13: add `allow_auto_create` setting
    // version 14: remove watches and triggered watches, these are now system indices
    // Note: if you change this, also inform the kibana team around the watcher-ui
    public static final int INDEX_TEMPLATE_VERSION = 13;
    public static final int INDEX_TEMPLATE_VERSION_10 = 10;
    public static final int INDEX_TEMPLATE_VERSION_11 = 11;
    public static final int INDEX_TEMPLATE_VERSION_12 = 12;
    public static final String HISTORY_TEMPLATE_NAME = ".watch-history-" + INDEX_TEMPLATE_VERSION;
    public static final String HISTORY_TEMPLATE_NAME_10 = ".watch-history-" + INDEX_TEMPLATE_VERSION_10;
    public static final String HISTORY_TEMPLATE_NAME_11 = ".watch-history-" + INDEX_TEMPLATE_VERSION_11;
    public static final String HISTORY_TEMPLATE_NAME_12 = ".watch-history-" + INDEX_TEMPLATE_VERSION_12;
    public static final String HISTORY_TEMPLATE_NAME_NO_ILM = ".watch-history-no-ilm-" + INDEX_TEMPLATE_VERSION;
    public static final String HISTORY_TEMPLATE_NAME_NO_ILM_10 = ".watch-history-no-ilm-" + INDEX_TEMPLATE_VERSION_10;
    public static final String HISTORY_TEMPLATE_NAME_NO_ILM_11 = ".watch-history-no-ilm-" + INDEX_TEMPLATE_VERSION_11;
    public static final String HISTORY_TEMPLATE_NAME_NO_ILM_12 = ".watch-history-no-ilm-" + INDEX_TEMPLATE_VERSION_12;
    public static final String[] TEMPLATE_NAMES = new String[] { HISTORY_TEMPLATE_NAME };
    public static final String[] TEMPLATE_NAMES_NO_ILM = new String[] { HISTORY_TEMPLATE_NAME_NO_ILM };

    private WatcherIndexTemplateRegistryField() {}
}
