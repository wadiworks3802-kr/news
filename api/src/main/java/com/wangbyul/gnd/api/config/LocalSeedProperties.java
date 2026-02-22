package com.wangbyul.gnd.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
/**
 * LocalSeedProperties 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Component
@ConfigurationProperties(prefix = "app.seed")
public class LocalSeedProperties {

    private boolean enabled = true;
    private boolean sampleNewsEnabled = false;
    private int itemsPerCategory = 5;
    private boolean resetOnStart = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getItemsPerCategory() {
        return itemsPerCategory;
    }

    public void setItemsPerCategory(int itemsPerCategory) {
        this.itemsPerCategory = itemsPerCategory;
    }

    public boolean isSampleNewsEnabled() {
        return sampleNewsEnabled;
    }

    public void setSampleNewsEnabled(boolean sampleNewsEnabled) {
        this.sampleNewsEnabled = sampleNewsEnabled;
    }

    public boolean isResetOnStart() {
        return resetOnStart;
    }

    public void setResetOnStart(boolean resetOnStart) {
        this.resetOnStart = resetOnStart;
    }
}
