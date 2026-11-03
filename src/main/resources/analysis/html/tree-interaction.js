        document.addEventListener('DOMContentLoaded', function () {
            createParticles();

            naviToTarget();
        });

        function createParticles() {
            const particlesContainer = document.getElementById('particles');

            const particleCount = 20;

            const colors = [
                'rgba(0, 200, 255, 0.8)',
                'rgba(255, 0, 200, 0.8)',
                'rgba(0, 255, 100, 0.8)',
                'rgba(255, 200, 0, 0.8)',
                'rgba(100, 150, 255, 0.8)'
            ];
            for (let i = 0; i < particleCount; i++) {
                const particle = document.createElement('div');
                particle.className = 'particle';

                const size = Math.random() * 100 + 10;
                const x = Math.random() * 100;
                const y = Math.random() * 100;
                const duration = Math.random() * 15 + 10;
                const color = colors[Math.floor(Math.random() * colors.length)];

                particle.style.cssText = `position: absolute; width: ${size}px; height: ${size}px; left: ${x}%; top: ${y}%; background: radial-gradient(circle, ${color} 0%, rgba(0,0,0,0) 70%); border-radius: 50%; animation: float ${duration}s infinite 0s linear; pointer-events: none;`;

                particlesContainer.appendChild(particle);
            }
        }

        function showNotification(text) {
            const existingNotification = document.querySelector('.notification');
            if (existingNotification) {
                existingNotification.remove();
            }

            const notification = document.createElement('div');
            notification.className = 'notification';
            notification.textContent = text;

            document.body.appendChild(notification);

            setTimeout(() => {
                notification.style.transform = 'translateX(0)';
                notification.style.opacity = '1';
            }, 10);

            setTimeout(() => {
                notification.style.transform = 'translateX(100px)';
                notification.style.opacity = '0';

                setTimeout(() => {
                    notification.remove();
                }, 500);
            }, 3000);
        }

        function expandAll() {
            const rootClassDivList = document.querySelectorAll('[data-root]');
            for (let i = 0; i < rootClassDivList.length; i++) {
                expandClassDiv(rootClassDivList[i], true);
            }
        }

        function collapseAll() {
            const rootClassDivList = document.querySelectorAll('[data-root]');
            for (let i = rootClassDivList.length - 1; i >= 0; i--) {
                collapseClassDiv(rootClassDivList[i], true);
            }
        }

        /**
         * 切换粒子特效层级：-1 把粒子压到内容之后，只解决遮挡问题，不关动画。
         */
        function switchEffectsLevel() {
            const particlesContainer = document.getElementById('particles');
            if (particlesContainer.style.zIndex === '-1') {
                particlesContainer.style.zIndex = '0';
                showNotification('特效层级已恢复');
            } else {
                particlesContainer.style.zIndex = '-1';
                showNotification('特效层级已降低');
            }
        }

        /**
         * 展开/收起按钮的入口。
         *
         * <p>必须 stopPropagation：按钮嵌在类标题的 onclick 里，冒泡会连带触发导航，而导航沿祖先链展开
         * 当前元素，把刚收起的内容又摊开，表现为「点收起没反应」。
         */
        function changeDisplayForClassDiv(controlElement, event) {
            if (event) {
                event.stopPropagation();
            }
            let classId = controlElement.getAttribute('data-classId');
            let name = controlElement.getAttribute('data-name');
            if (name === '+') {
                expandClassDiv(document.getElementById(classId), false);
            } else if (name === '++') {
                expandClassDiv(document.getElementById(classId), true);
            } else if (name === '-') {
                collapseClassDiv(document.getElementById(classId), false);
            } else if (name === '--') {
                collapseClassDiv(document.getElementById(classId), true);
            }
        }

        function expandClassDiv(element, all) {
            setClassDivVisible(element, all, true);
        }

        function collapseClassDiv(element, all) {
            setClassDivVisible(element, all, false);
        }

        /**
         * 展开/收起的唯一实现。
         *
         * <p>按 {@code data-name} 分派：{@code ++} 只在「全部展开」时隐藏，否则保持原状；
         * {@code support-control} 是受控面板，非 all 时不往下递归，避免一次点击摊开整棵子树。
         */
        function setClassDivVisible(element, all, expand) {
            if (element.nodeType !== 1) {
                return;
            }
            const name = element.getAttribute('data-name');
            if (name === '+') {
                element.style.display = expand ? 'none' : '';
            } else if (name === '-') {
                element.style.display = expand ? '' : 'none';
            } else if (name === '++') {
                if (!expand || all) {
                    element.style.display = expand ? 'none' : '';
                }
            } else if (name === '--') {
                element.style.display = expand ? '' : 'none';
            } else if (element.hasAttribute('support-control')) {
                element.style.display = expand ? '' : 'none';
                if (!all) {
                    return;
                }
            }
            for (const child of element.childNodes) {
                setClassDivVisible(child, all, expand);
            }
        }

        /** 只写地址栏 hash 与历史记录，不做任何呈现动作。 */
        function writeHash(hash) {
            if (window.location.hash === hash) {
                return;
            }
            history.pushState({}, '', hash);
        }

        /**
         * 记录历史后立即按新 hash 呈现视图。
         *
         * <p>必须自己触发：pushState 不触发 popstate 也不触发 hashchange，只写 hash 等于点击没反应。
         */
        function setNaviHash(hash) {
            writeHash(hash);
            naviToTarget();
        }

        let isNowNavigating = false;
        function naviToTarget() {
            if (isNowNavigating) {
                showNotification("繁忙中，稍后再试！");
                return;
            }
            const hash = window.location.hash;
            if (!hash) {
                return;
            }
            const targetId = hash.substring(1);
            let target = document.getElementById(targetId);
            if (!target) {
                return;
            }
            isNowNavigating = true;
            let current = target;
            while (current && current !== document.body) {
                if (current.hasAttribute('data-expandable')) {
                    expandClassDiv(current, false);
                }
                current = current.parentElement;
            }
            writeHash('#' + targetId);
            highlightAndMoveTo(target);
            showNotification('跳转到' + describeNavTarget(target) + ': ' + targetId);
        }

        /** 按 data-kind 判定导航提示里的类型名，字段与类分开，避免点字段却提示“跳转到类”。 */
        function describeNavTarget(element) {
            const kind = element.getAttribute('data-kind');
            if (kind === 'field') {
                return '字段';
            }
            if (kind === 'class') {
                return '类';
            }
            return '节点';
        }

        function highlightAndMoveTo(targetElement) {
            requestAnimationFrame(() => {
                requestAnimationFrame(() => {
                    const oldTarget = document.querySelector(':target');
                    if (oldTarget) {
                        oldTarget.style.animation = 'none';
                        void oldTarget.offsetWidth;
                    }

                    targetElement.style.animation = 'highlight-target 2s';

                    setTimeout(() => {
                        const rect = targetElement.getBoundingClientRect();
                        const currentScrollTop = window.scrollY || document.documentElement.scrollTop;
                        const currentScrollLeft = window.scrollX || document.documentElement.scrollLeft;

                        let scrollTopOffset = 0;
                        let scrollLeftOffset = 0;

                        if (rect.top < 300) {
                            scrollTopOffset = rect.top - 300;
                        }

                        if (rect.left < 300) {
                            scrollLeftOffset = rect.left - 300;
                        }

                        if (scrollTopOffset !== 0 || scrollLeftOffset !== 0) {
                            window.scrollTo({
                                left: currentScrollLeft + scrollLeftOffset,
                                top: currentScrollTop + scrollTopOffset,
                                behavior: 'smooth'
                            });
                        }
                        isNowNavigating = false;
                    }, 100);
                });
            });
        }

        window.addEventListener('popstate', naviToTarget);
    