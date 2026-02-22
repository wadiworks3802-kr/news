package com.wangbyul.gnd.collector.service;

import com.wangbyul.gnd.core.domain.NewsEntity;
import com.wangbyul.gnd.core.service.NormalizeService;
import com.wangbyul.gnd.core.util.HashUtils;
import org.springframework.stereotype.Service;
/**
 * NormalizeServiceImpl 컴포넌트.
 *
 * 프로젝트 기능 구현을 위한 핵심 컴포넌트.
 *
 * 작성자 : 안태욱
 * 현재날짜 : 2026년 02월 20일
 */

@Service
public class NormalizeServiceImpl implements NormalizeService {

    private final UrlNormalizer urlNormalizer;

    public NormalizeServiceImpl(UrlNormalizer urlNormalizer) {
        this.urlNormalizer = urlNormalizer;
    }

    @Override
    public NewsEntity normalize(NewsEntity raw) {
        raw.setUrlNorm(urlNormalizer.normalize(raw.getUrl()));

        if (raw.getPubUtc() == null) {
            // As required: fallback to fetch_utc when pub_utc is missing.
            raw.setPubUtc(raw.getFetchUtc());
        }

        String hashInput = raw.getTitleRaw() + "\n" + raw.getBodyRaw() + "\n" + raw.getPubUtc();
        raw.setContentHash(HashUtils.sha256(hashInput));
        raw.setSimhash64(HashUtils.simHash64(raw.getTitleRaw() + " " + raw.getBodyRaw()));
        return raw;
    }
}
