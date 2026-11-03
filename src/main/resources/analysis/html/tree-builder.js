        /**
         * 把 JSON 数据骨架还原成 DOM 树。
         *
         * <p>这里是 HTML2 与服务端渲染的 HTML 之间的唯一契约点：建出来的节点必须与那些
         * 「服务端拼标签」的产物完全一致（类名、id、data-* 属性、嵌套层级），否则共用同一份
         * 交互脚本的展开/收起与导航会失效，而页面看起来只是「点了没反应」。
         *
         * <p>可推导的信息一律不写进数据：锚点文本 #id# 用 id 拼、按钮显隐取自 defaultToExpand、
         * 「展开/收起」按钮是否出现由 fields 是否为空 + complex 推导。
         */
        (function () {
            'use strict';

            const container = document.getElementById('container');
            const dataElement = document.getElementById('analysis-data');
            if (!container || !dataElement) {
                return;
            }

            let skeleton;
            try {
                skeleton = JSON.parse(dataElement.textContent);
            } catch (error) {
                container.textContent = '数据骨架解析失败：' + error.message;
                return;
            }

            const defaultToExpand = skeleton.defaultToExpand === true;

            function element(tag, className) {
                const node = document.createElement(tag);
                if (className) {
                    node.className = className;
                }
                return node;
            }

            function span(className, text) {
                const node = element('span', className);
                node.textContent = text;
                return node;
            }

            function controlButton(classId, name, text, cursor, visible) {
                const button = element('a', 'class_model_title_control_button');
                button.id = classId + name;
                button.setAttribute('href', 'javascript:');
                button.setAttribute('onclick', 'changeDisplayForClassDiv(this, event)');
                button.setAttribute('data-classId', classId);
                button.setAttribute('data-name', name);
                button.style.cursor = cursor;
                if (!visible) {
                    button.style.display = 'none';
                }
                button.textContent = text;
                return button;
            }

            function appendControlButtons(titleElement, classId, basic, all) {
                if (basic) {
                    titleElement.appendChild(controlButton(classId, '+', '[展开]', 'zoom-in', !defaultToExpand));
                    titleElement.appendChild(controlButton(classId, '-', '[收起]', 'zoom-out', defaultToExpand));
                }
                if (all) {
                    titleElement.appendChild(controlButton(classId, '++', '[全部展开]', 'zoom-in', !defaultToExpand));
                    titleElement.appendChild(controlButton(classId, '--', '[全部收起]', 'zoom-out', defaultToExpand));
                }
            }

            function appendTitleFragments(titleElement, title, classId) {
                if (title) {
                    if (title.modifier) {
                        titleElement.appendChild(span('class_model_title_modifier_type', title.modifier));
                    }
                    if (title.name !== undefined && title.name !== null) {
                        titleElement.appendChild(span('class_model_title_display_name', title.name));
                    }
                    if (title.instance) {
                        titleElement.appendChild(span('class_model_title_instance_flag', '*'));
                    }
                }
                titleElement.appendChild(span('class_model_title_display_id', '#' + classId + '#'));
            }

            function buildClassTitle(node) {
                const titleElement = element('pre', 'class_model_title');
                titleElement.setAttribute('onclick', "setNaviHash('#" + node.id + "')");
                if (node.super) {
                    titleElement.appendChild(span('class_model_title_super_flag', '[super]'));
                }
                appendTitleFragments(titleElement, node.title, node.id);
                const basic = (node.fields || []).length > 0 || node.complex === true;
                appendControlButtons(titleElement, node.id, basic, node.complex === true);
                return titleElement;
            }

            function buildClassContent(node) {
                const content = element('div', 'class_model_content');
                content.id = node.id + '-content';
                content.setAttribute('support-control', '');
                if (!defaultToExpand) {
                    content.style.display = 'none';
                }
                const fields = node.fields || [];
                for (let i = 0; i < fields.length; i++) {
                    content.appendChild(buildField(fields[i]));
                }
                if (node.superClass) {
                    content.appendChild(buildClass(node.superClass));
                }
                return content;
            }

            function buildClass(node) {
                if (node.type === 'link') {
                    return buildLinkClass(node);
                }
                if (node.type === 'info') {
                    return buildInfoClass(node);
                }
                const box = element('div', node.root ? 'root_class_model' : (node.super ? 'super_class_model' : 'class_model'));
                box.id = node.id;
                box.setAttribute('data-expandable', 'true');
                box.setAttribute('data-kind', 'class');
                if (node.root) {
                    box.setAttribute('data-root', 'true');
                }
                box.appendChild(buildClassTitle(node));
                box.appendChild(buildClassContent(node));
                return box;
            }

            function buildInfoClass(node) {
                const box = element('div', 'class_model common_info_model');
                box.id = node.id;
                box.setAttribute('data-kind', 'class');
                const pre = element('pre');
                pre.textContent = node.text === undefined || node.text === null ? '' : node.text;
                box.appendChild(pre);
                return box;
            }

            function buildLinkClass(node) {
                const anchor = element('a', 'link_class_model_container');
                anchor.setAttribute('href', 'javascript:');
                anchor.setAttribute('onclick', 'setNaviHash("#' + node.target + '")');
                const pre = element('pre');
                if (node.super) {
                    pre.appendChild(span('class_model_title_super_flag', '[super]'));
                }
                appendTitleFragments(pre, node.title, node.id);
                anchor.appendChild(pre);

                const box = element('div', 'link_class_model');
                box.id = node.id;
                box.appendChild(anchor);
                if (!node.super) {
                    return box;
                }
                const wrapper = element('div', 'super_class_model');
                wrapper.appendChild(box);
                return wrapper;
            }

            function hasFieldValue(node) {
                return node.value !== undefined || node.masked !== undefined
                    || node.size !== undefined || node.inline !== undefined;
            }

            function buildField(node) {
                if (node.type === 'infoField') {
                    return buildInfoField(node);
                }
                const box = element('div', 'field_model');
                box.id = node.id;
                box.setAttribute('data-kind', 'field');

                const pre = element('pre', 'field_model_info');
                const titleElement = element('span', 'field_model_info_title');
                titleElement.setAttribute('onclick', "setNaviHash('#" + node.id + "')");
                if (node.static) {
                    titleElement.appendChild(span('field_model_info_static_flag', 'static'));
                }
                if (node.final) {
                    titleElement.appendChild(span('field_model_info_final_flag', 'final'));
                }
                const nameElement = element('b', 'field_model_info_name');
                nameElement.textContent = node.name === undefined ? '' : node.name;
                titleElement.appendChild(nameElement);
                const typeElement = element('i', 'field_model_info_type');
                typeElement.textContent = node.fieldType === undefined ? '' : node.fieldType;
                titleElement.appendChild(typeElement);
                if (hasFieldValue(node)) {
                    titleElement.appendChild(span('field_model_info_equal_flag', '='));
                    if (node.masked !== undefined) {
                        titleElement.appendChild(span('field_model_info_value', node.masked));
                    } else if (node.value !== undefined) {
                        titleElement.appendChild(span('field_model_info_value', node.value));
                    } else {
                        if (node.size !== undefined && node.size >= 0) {
                            titleElement.appendChild(span('field_model_info_size', '(' + node.size + ')'));
                        }
                        if (node.inline !== undefined) {
                            titleElement.appendChild(span('field_model_info_equal_flag', '='));
                            titleElement.appendChild(span('field_model_info_value', '[' + node.inline + ']'));
                        }
                    }
                }
                pre.appendChild(titleElement);
                box.appendChild(pre);

                const classes = node.classes || [];
                if (classes.length === 1) {
                    box.appendChild(buildClass(classes[0]));
                } else if (classes.length > 1) {
                    box.appendChild(buildFieldClasses(node.id, classes));
                }
                return box;
            }

            function buildInfoField(node) {
                const box = element('div', 'field_model common_info_model');
                box.id = node.id;
                box.setAttribute('data-kind', 'field');
                const pre = element('pre', 'field_model_info');
                pre.textContent = node.text === undefined || node.text === null ? '' : node.text;
                box.appendChild(pre);
                return box;
            }

            /** 一个字段对应多个类模型时的容器：标题只有四个控制按钮，没有标题片段。 */
            function buildFieldClasses(fieldId, classes) {
                const holder = element('div', 'field_classes_container');
                holder.setAttribute('data-expandable', 'true');
                const titleElement = element('pre', 'class_model_title');
                titleElement.setAttribute('onclick', "setNaviHash('#" + fieldId + "')");
                appendControlButtons(titleElement, fieldId, true, true);
                holder.appendChild(titleElement);

                const panel = element('div', 'field_classes_container_panel');
                panel.setAttribute('support-control', '');
                if (!defaultToExpand) {
                    panel.style.display = 'none';
                }
                for (let i = 0; i < classes.length; i++) {
                    panel.appendChild(buildClass(classes[i]));
                }
                holder.appendChild(panel);
                return holder;
            }

            const roots = skeleton.classes || [];
            for (let i = 0; i < roots.length; i++) {
                container.appendChild(buildClass(roots[i]));
            }
        })();
