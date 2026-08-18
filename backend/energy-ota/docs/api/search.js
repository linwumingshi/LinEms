let api = [];
const apiDocListSize = 1
api.push({
    name: 'default',
    order: '1',
    list: []
})
api[0].list.push({
    alias: 'PingController',
    order: '1',
    link: '通用存活探针：所有依赖_energy-common_的_servlet_服务自动获得_/ping。_用于网关连通性与健康检查（phase_3_起）。',
    desc: '通用存活探针：所有依赖 energy-common 的 Servlet 服务自动获得 /ping。 用于网关连通性与健康检查（Phase 3 起）。',
    list: []
})
api[0].list[0].list.push({
    order: '1',
    deprecated: 'false',
    url: '/ping',
    methodId: '9672fbfee12feb7b0a7c2f282e1dc592',
    desc: '存活探针：返回服务名、版本与当前时间，供网关/负载均衡做连通性与健康检查。',
});
api[0].list.push({
    alias: 'OtaTaskController',
    order: '2',
    link: 'ota_批次任务_api（网关路由_/api/ota/**_→_energy-ota）。  &amp;lt;ul&amp;gt; &amp;lt;li&amp;gt;post_/api/ota/tasks_创建批次任务（设备快照_+_立即开始）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;get_/api/ota/tasks_任务分页；get_/api/ota/tasks/{taskid}_任务详情；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;get_/api/ota/tasks/{taskid}/devices_设备明细分页；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;post_/api/ota/tasks/{taskid}/start_立即开始；post_/api/ota/tasks/{taskid}/pause_暂停；_post /api/ota/tasks/{taskid}/resume_恢复；post_/api/ota/tasks/{taskid}/gray/advance_推进灰度；_post /api/ota/tasks/{taskid}/cancel_取消；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;get_/api/ota/tasks/{taskid}/statistics_成功率/统计。&amp;lt;/li&amp;gt; &amp;lt;/ul&amp;gt;',
    desc: 'OTA 批次任务 API（网关路由 /api/ota/** → energy-ota）。  &amp;lt;ul&amp;gt; &amp;lt;li&amp;gt;POST /api/ota/tasks 创建批次任务（设备快照 + 立即开始）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;GET /api/ota/tasks 任务分页；GET /api/ota/tasks/{taskId} 任务详情；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;GET /api/ota/tasks/{taskId}/devices 设备明细分页；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;POST /api/ota/tasks/{taskId}/start 立即开始；POST /api/ota/tasks/{taskId}/pause 暂停； POST /api/ota/tasks/{taskId}/resume 恢复；POST /api/ota/tasks/{taskId}/gray/advance 推进灰度； POST /api/ota/tasks/{taskId}/cancel 取消；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;GET /api/ota/tasks/{taskId}/statistics 成功率/统计。&amp;lt;/li&amp;gt; &amp;lt;/ul&amp;gt;',
    list: []
})
api[0].list[1].list.push({
    order: '1',
    deprecated: 'false',
    url: '/api/ota/tasks',
    methodId: '2b231b007fb27bc799921c92102a5295',
    desc: '创建 OTA 批次升级任务（拍摄目标设备快照并写入任务与设备明细）。 &lt;p&gt; 若请求未指定 planTime（scheduleTime 为 NULL），则创建后立即开始执行。',
});
api[0].list[1].list.push({
    order: '2',
    deprecated: 'false',
    url: '/api/ota/tasks',
    methodId: 'c2921c9c83fc47864251f5a3e7c957b6',
    desc: '批次任务分页查询，支持按任务名、状态筛选。',
});
api[0].list[1].list.push({
    order: '3',
    deprecated: 'false',
    url: '/api/ota/tasks/{taskId}',
    methodId: '664b89b27614cc350379a24d3acfc4f5',
    desc: '查询单个批次任务详情。',
});
api[0].list[1].list.push({
    order: '4',
    deprecated: 'false',
    url: '/api/ota/tasks/{taskId}/devices',
    methodId: 'bb6a615dd79e8419720e3f0454b474d8',
    desc: '查询批次任务下的设备升级明细分页，可按设备状态筛选。',
});
api[0].list[1].list.push({
    order: '5',
    deprecated: 'false',
    url: '/api/ota/tasks/{taskId}/start',
    methodId: 'a0e5c61084275221c37281ee7b654a37',
    desc: '立即开始执行批次任务（向目标设备下发升级指令）。',
});
api[0].list[1].list.push({
    order: '6',
    deprecated: 'false',
    url: '/api/ota/tasks/{taskId}/pause',
    methodId: '4c2ebdafc680c21d899278957d561e44',
    desc: '暂停执行中的批次任务（已下发的设备继续当前流程，新设备停止下发）。',
});
api[0].list[1].list.push({
    order: '7',
    deprecated: 'false',
    url: '/api/ota/tasks/{taskId}/resume',
    methodId: '5babf478637ef36bbf61b7d7e39b000d',
    desc: '恢复已暂停的批次任务，继续向剩余设备下发升级。',
});
api[0].list[1].list.push({
    order: '8',
    deprecated: 'false',
    url: '/api/ota/tasks/{taskId}/gray/advance',
    methodId: 'a4bce0c5234aded24ceff81862ba69c9',
    desc: '推进灰度批次任务到下一灰度批次（按比例扩大设备覆盖范围）。',
});
api[0].list[1].list.push({
    order: '9',
    deprecated: 'false',
    url: '/api/ota/tasks/{taskId}/cancel',
    methodId: 'd38a397bb0c8c2f31640579e883a772c',
    desc: '取消批次任务（终止剩余设备下发，已成功设备不受影响）。',
});
api[0].list[1].list.push({
    order: '10',
    deprecated: 'false',
    url: '/api/ota/tasks/{taskId}/statistics',
    methodId: '241bc37dd5ed3397b2e667c8ac4da9db',
    desc: '统计批次任务成功率及设备状态分布（基于任务与设备明细聚合）。',
});
api[0].list.push({
    alias: 'OtaPackageController',
    order: '3',
    link: 'ota_升级包_api（网关路由_/api/ota/**_→_energy-ota）。  &amp;lt;ul&amp;gt; &amp;lt;li&amp;gt;post_/api/ota/packages_上传升级包（multipart，自动_rsa_签名）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;post_/api/ota/packages/{packageid}/diff_平台生成差分包（s5-1）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;get_/api/ota/packages/{packageid}/verify-signature_验签（文件_sha256_+_rsa_签名）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;get_/api/ota/packages/{packageid}/url_生成签名下载_url；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;get_/api/ota/packages_升级包分页查询；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;get_/api/ota/packages/{packageid}_升级包详情；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;put_/api/ota/packages/{packageid}/status_更新升级包状态（启用/停用）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;delete_/api/ota/packages/{packageid}_删除升级包；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;get_/api/ota/files/**_升级包文件下载——签名_url_校验（s5-4）+_http_range_分片/断点续传（206_partial content）。&amp;lt;/li&amp;gt; &amp;lt;/ul&amp;gt;',
    desc: 'OTA 升级包 API（网关路由 /api/ota/** → energy-ota）。  &amp;lt;ul&amp;gt; &amp;lt;li&amp;gt;POST /api/ota/packages 上传升级包（multipart，自动 RSA 签名）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;POST /api/ota/packages/{packageId}/diff 平台生成差分包（S5-1）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;GET /api/ota/packages/{packageId}/verify-signature 验签（文件 SHA256 + RSA 签名）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;GET /api/ota/packages/{packageId}/url 生成签名下载 URL；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;GET /api/ota/packages 升级包分页查询；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;GET /api/ota/packages/{packageId} 升级包详情；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;PUT /api/ota/packages/{packageId}/status 更新升级包状态（启用/停用）；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;DELETE /api/ota/packages/{packageId} 删除升级包；&amp;lt;/li&amp;gt; &amp;lt;li&amp;gt;GET /api/ota/files/** 升级包文件下载——签名 URL 校验（S5-4）+ HTTP Range 分片/断点续传（206 Partial Content）。&amp;lt;/li&amp;gt; &amp;lt;/ul&amp;gt;',
    list: []
})
api[0].list[2].list.push({
    order: '1',
    deprecated: 'false',
    url: '/api/ota/packages',
    methodId: '49e14a0a05a8821e8cbc6c223b767e34',
    desc: '上传升级包（multipart/form-data，包含固件文件与元数据），服务端自动计算文件摘要（MD5/SHA256）并生成 RSA 签名。',
});
api[0].list[2].list.push({
    order: '2',
    deprecated: 'false',
    url: '/api/ota/packages/{packageId}/diff',
    methodId: 'c53e93e18fd2526da3515102bcfddcba',
    desc: '平台基于两个全量包生成差分包（basePackageId 为源全量包，packageId 为目标全量包）。 &lt;p&gt; 当差分无收益时退化为全量下发，此时 diffPackageId 返回空字符串并提示“差分无收益，已退化为全量下发”。',
});
api[0].list[2].list.push({
    order: '3',
    deprecated: 'false',
    url: '/api/ota/packages/{packageId}/verify-signature',
    methodId: '096e8bd826f9fb87e844cd0bb9d456a7',
    desc: '校验升级包文件完整性：比对文件 SHA256 与平台 RSA 签名是否可用公钥验签通过。',
});
api[0].list[2].list.push({
    order: '4',
    deprecated: 'false',
    url: '/api/ota/packages/{packageId}/url',
    methodId: '7c2749945141804a580a7a6da11bafb7',
    desc: '为指定升级包预生成带时效的 HMAC-SHA256 签名下载 URL（供管理端或下发信封使用）。',
});
api[0].list[2].list.push({
    order: '5',
    deprecated: 'false',
    url: '/api/ota/packages',
    methodId: 'c7eacb5ac4477e01f578d939b5fedefe',
    desc: '升级包分页查询，支持按产品、版本、包类型、状态筛选。',
});
api[0].list[2].list.push({
    order: '6',
    deprecated: 'false',
    url: '/api/ota/packages/{packageId}',
    methodId: 'c7a4cfd1f429ed1945d6c27b4120b15b',
    desc: '查询单个升级包详情。',
});
api[0].list[2].list.push({
    order: '7',
    deprecated: 'false',
    url: '/api/ota/packages/{packageId}/status',
    methodId: '9c224a691e81cbbcfae14dcf4a8e79ba',
    desc: '更新升级包状态（启用/停用）。',
});
api[0].list[2].list.push({
    order: '8',
    deprecated: 'false',
    url: '/api/ota/packages/{packageId}',
    methodId: '061889df8b1168ce5b5e89ec0f254c34',
    desc: '删除升级包（软删除，置 deleted 标记）。',
});
api[0].list[2].list.push({
    order: '9',
    deprecated: 'false',
    url: '/api/ota/files/**',
    methodId: '33567678a7346fbfdfda51fdeb4e34bf',
    desc: '升级包文件下载——双模式鉴权 + HTTP Range 分片/断点续传。 路径：/api/ota/files/{productKey}/{version}/{module}/{fileName}[?expires=&amp;sign=] &lt;ul&gt; &lt;li&gt;设备/信封模式：携带合法 {@code expires+sign} 签名 URL（S5-4），无需登录；过期/篡改 → 403；&lt;/li&gt; &lt;li&gt;管理端模式：无签名参数时要求合法登录态 JWT（与网关同源验签），未登录/无效 → 401；&lt;/li&gt; &lt;li&gt;带 Range 头 → 206 Partial Content（分片断点续传，块大小 segmentSize）；&lt;/li&gt; &lt;li&gt;普通 GET → 200 全量。&lt;/li&gt; &lt;/ul&gt;',
});
document.onkeydown = keyDownSearch;
function keyDownSearch(e) {
    const theEvent = e;
    const code = theEvent.keyCode || theEvent.which || theEvent.charCode;
    if (code === 13) {
        const search = document.getElementById('search');
        const searchValue = search.value.toLocaleLowerCase();

        let searchGroup = [];
        for (let i = 0; i < api.length; i++) {

            let apiGroup = api[i];

            let searchArr = [];
            for (let i = 0; i < apiGroup.list.length; i++) {
                let apiData = apiGroup.list[i];
                const desc = apiData.desc;
                if (desc.toLocaleLowerCase().indexOf(searchValue) > -1) {
                    searchArr.push({
                        order: apiData.order,
                        desc: apiData.desc,
                        link: apiData.link,
                        alias: apiData.alias,
                        list: apiData.list
                    });
                } else {
                    let methodList = apiData.list || [];
                    let methodListTemp = [];
                    for (let j = 0; j < methodList.length; j++) {
                        const methodData = methodList[j];
                        const methodDesc = methodData.desc;
                        if (methodDesc.toLocaleLowerCase().indexOf(searchValue) > -1) {
                            methodListTemp.push(methodData);
                            break;
                        }
                    }
                    if (methodListTemp.length > 0) {
                        const data = {
                            order: apiData.order,
                            desc: apiData.desc,
                            link: apiData.link,
                            alias: apiData.alias,
                            list: methodListTemp
                        };
                        searchArr.push(data);
                    }
                }
            }
            if (apiGroup.name.toLocaleLowerCase().indexOf(searchValue) > -1) {
                searchGroup.push({
                    name: apiGroup.name,
                    order: apiGroup.order,
                    list: searchArr
                });
                continue;
            }
            if (searchArr.length === 0) {
                continue;
            }
            searchGroup.push({
                name: apiGroup.name,
                order: apiGroup.order,
                list: searchArr
            });
        }
        let html;
        if (searchValue === '') {
            const liClass = "";
            const display = "display: none";
            html = buildAccordion(api,liClass,display);
            document.getElementById('accordion').innerHTML = html;
        } else {
            const liClass = "open";
            const display = "display: block";
            html = buildAccordion(searchGroup,liClass,display);
            document.getElementById('accordion').innerHTML = html;
        }
        const Accordion = function (el, multiple) {
            this.el = el || {};
            this.multiple = multiple || false;
            const links = this.el.find('.dd');
            links.on('click', {el: this.el, multiple: this.multiple}, this.dropdown);
        };
        Accordion.prototype.dropdown = function (e) {
            const $el = e.data.el;
            let $this = $(this), $next = $this.next();
            $next.slideToggle();
            $this.parent().toggleClass('open');
            if (!e.data.multiple) {
                $el.find('.submenu').not($next).slideUp("20").parent().removeClass('open');
            }
        };
        new Accordion($('#accordion'), false);
    }
}

function buildAccordion(apiGroups, liClass, display) {
    let html = "";
    if (apiGroups.length > 0) {
        if (apiDocListSize === 1) {
            let apiData = apiGroups[0].list;
            let order = apiGroups[0].order;
            for (let j = 0; j < apiData.length; j++) {
                html += '<li class="'+liClass+'">';
                html += '<a class="dd" href="#' + apiData[j].alias + '">' + apiData[j].order + '.&nbsp;' + apiData[j].desc + '</a>';
                html += '<ul class="sectlevel2" style="'+display+'">';
                let doc = apiData[j].list;
                for (let m = 0; m < doc.length; m++) {
                    let spanString;
                    if (doc[m].deprecated === 'true') {
                        spanString='<span class="line-through">';
                    } else {
                        spanString='<span>';
                    }
                    html += '<li><a href="#' + doc[m].methodId + '">' + apiData[j].order + '.' + doc[m].order + '.&nbsp;' + spanString + doc[m].desc + '<span></a> </li>';
                }
                html += '</ul>';
                html += '</li>';
            }
        } else {
            for (let i = 0; i < apiGroups.length; i++) {
                let apiGroup = apiGroups[i];
                html += '<li class="'+liClass+'">';
                html += '<a class="dd" href="#_'+apiGroup.order+'_' + apiGroup.name + '">' + apiGroup.order + '.&nbsp;' + apiGroup.name + '</a>';
                html += '<ul class="sectlevel1">';

                let apiData = apiGroup.list;
                for (let j = 0; j < apiData.length; j++) {
                    html += '<li class="'+liClass+'">';
                    html += '<a class="dd" href="#' + apiData[j].alias + '">' +apiGroup.order+'.'+ apiData[j].order + '.&nbsp;' + apiData[j].desc + '</a>';
                    html += '<ul class="sectlevel2" style="'+display+'">';
                    let doc = apiData[j].list;
                    for (let m = 0; m < doc.length; m++) {
                       let spanString;
                       if (doc[m].deprecated === 'true') {
                           spanString='<span class="line-through">';
                       } else {
                           spanString='<span>';
                       }
                       html += '<li><a href="#' + doc[m].methodId + '">'+apiGroup.order+'.' + apiData[j].order + '.' + doc[m].order + '.&nbsp;' + spanString + doc[m].desc + '<span></a> </li>';
                   }
                    html += '</ul>';
                    html += '</li>';
                }

                html += '</ul>';
                html += '</li>';
            }
        }
    }
    return html;
}