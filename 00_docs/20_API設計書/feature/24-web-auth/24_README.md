# 24_README

邵ｺ阮吶・郢昴・縺・ｹ晢ｽｬ郢ｧ・ｯ郢晏現ﾎ懃ｸｺ・ｯ API `web-auth` 隶匁ｺｯ繝ｻ邵ｺ・ｮ髫ｪ・ｭ髫ｪ蝓溷ｶ檎ｸｺ・ｧ邵ｺ蜷ｶﾂ繝ｻ
API `web-auth` 邵ｺ・ｯ邵ｲ・｣lugin 邵ｺ荵晢ｽ臥ｸｺ・ｮ郢晢ｽｭ郢ｧ・ｰ郢ｧ・､郢晢ｽｳ郢昶・ﾎ慕ｹ晢ｽｬ郢晢ｽｳ郢ｧ・ｸ騾具ｽｺ髯ｦ迹夲ｽｦ竏ｵ・ｱ繧・・邵ｲ莉戲B 邵ｺ荵晢ｽ臥ｸｺ・ｮ郢晢ｽｭ郢ｧ・ｰ郢ｧ・､郢晢ｽｳ郢昶・ﾎ慕ｹ晢ｽｬ郢晢ｽｳ郢ｧ・ｸ雎ｸ驛・ｽｲ・ｻ髫補扱・ｱ繧・ｽ定ｬ・ｽｱ邵ｺ繝ｻﾂ繧・・郢晢ｽｬ郢ｧ・､郢晢ｽ､郢晢ｽｼ隴幢ｽｬ闔・ｺ驕抵ｽｺ髫ｱ髦ｪ繝ｻ隘搾ｽｷ霓､・ｹ邵ｺ・ｯ Plugin 邵ｺ・ｮ `/web login` 陞ｳ貅ｯ・｡蠕後堤ｸｺ繧・ｽ顔ｸｲ縲￣I 邵ｺ・ｯ驕擾ｽｭ陷ｻ・ｽ郢晢ｽｻ闕ｳﾂ陜玲ｨ｣蜑樒ｹｧ鄙ｫ繝ｻ郢ｧ・ｳ郢晢ｽｼ郢晏ｳｨ・・DB 邵ｺ・ｫ闖ｫ譎擾ｽｭ蛟･・邵ｲ莉戲B 邵ｺ荵晢ｽ臥ｸｺ・ｮ陷茨ｽ･陷牙ｸｶ蜃ｾ邵ｺ・ｫ隶諛・ｽｨ・ｼ邵ｺ蜉ｱ窶ｻ郢晢ｽｭ郢ｧ・ｰ郢ｧ・､郢晢ｽｳ闕ｳ・ｻ闖ｴ阮呻ｽ帝￡・ｺ陞ｳ螢ｹ笘・ｹｧ荵敖繝ｻ
## 陝・ｽｾ髮趣ｽ｡陞ｳ貅ｯ・｣繝ｻ繝ｱ郢ｧ・ｹ

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/WebAuthController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/WebAuthModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IWebAuthRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/WebAuthRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Services/WebAuthService.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/WebLoginChallengeEntity.cs`

## 郢晏ｳｨ縺冗ｹ晢ｽ･郢晢ｽ｡郢晢ｽｳ郢昜ｺ包ｽｸﾂ髫包ｽｧ

1. [[24_0.00-隶弱ｊ・ｦ窶疹
2. [[24_1.00-郢晢ｽ｢郢昴・ﾎ晁楜螟ゑｽｾ・ｩ]]
3. [[24_2.00-郢晢ｽｦ郢晢ｽｼ郢ｧ・ｹ郢ｧ・ｱ郢晢ｽｼ郢ｧ・ｹ]]
4. [[24_3.00-驍擾ｽ｢陟題ｩ評
5. [[24_3.02-騾具ｽｻ鬪ｭ・ｲ驍会ｽｻ]]
6. [[24_3.03-雎ｸ驛・ｽｲ・ｻ驍会ｽｻ]]
7. [[24_4.00-驍ｨ・ｱ陷ｷ蛹ｻ繝ｵ郢晢ｽｭ郢晢ｽｼ]]
8. [[24_5.00-關灘唱・､謔ｶ繝ｻ郢晢ｽｭ郢ｧ・ｰ郢晢ｽｻ鬩慕距逡曽]
9. [[24_9.00-隴幢ｽｪ雎趣ｽｺ闔遏ｩ・ｰ繝ｻ]

## 鬮｢・｢鬨ｾ・｣髫ｪ・ｭ髫ｪ繝ｻ
- Plugin: `00_docs/10_郢晏干ﾎ帷ｹｧ・ｰ郢ｧ・､郢晢ｽｳ髫ｪ・ｭ髫ｪ蝓溷ｶ・feature/24-web-auth`
- Web: `00_docs/30_WEB髫ｪ・ｭ髫ｪ蝓溷ｶ・feature/01-web-auth`
- DB: `00_docs/40_Database髫ｪ・ｭ髫ｪ蝓溷ｶ・table-definitions/AstralRecord/dbo.web_login_challenge.md`

## 隴厄ｽｴ隴・ｽｰ郢晢ｽｫ郢晢ｽｼ郢晢ｽｫ

- DTO 陞溽判蟲ｩ: [[24_1.00-郢晢ｽ｢郢昴・ﾎ晁楜螟ゑｽｾ・ｩ]] 邵ｺ・ｨ髫ｧ・ｲ陟冶侭縺顔ｹ晢ｽｳ郢晏ｳｨ繝ｻ郢ｧ・､郢晢ｽｳ郢晏現・定ｭ厄ｽｴ隴・ｽｰ邵ｺ蜷ｶ・狗ｸｲ繝ｻ- 郢ｧ・ｨ郢晢ｽｳ郢晏ｳｨ繝ｻ郢ｧ・､郢晢ｽｳ郢晞メ・ｿ・ｽ陷会｣ｰ郢晢ｽｻ陷台ｼ∝求: [[24_3.00-驍擾ｽ｢陟題ｩ評 郢ｧ蜻亥ｳｩ隴・ｽｰ邵ｺ蜷ｶ・狗ｸｲ繝ｻ- 郢昶・ﾎ慕ｹ晢ｽｬ郢晢ｽｳ郢ｧ・ｸ闖ｫ譎擾ｽｭ蛟・ｽｻ蠅難ｽｧ莨懶ｽ､逕ｻ蟲ｩ: DB 陋幢ｽｴ `dbo.web_login_challenge.md` 郢ｧ蜻亥ｳｩ隴・ｽｰ邵ｺ蜷ｶ・狗ｸｲ繝ｻ- WEB Cookie 郢ｧ・ｻ郢昴・縺咏ｹ晢ｽｧ郢晢ｽｳ邵ｺ・ｮ Claim 陞溽判蟲ｩ: Web 陋幢ｽｴ `01-web-auth` 郢ｧ繧亥ｳｩ隴・ｽｰ邵ｺ蜷ｶ・狗ｸｲ繝ｻ
