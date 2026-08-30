const puppeteer = require('puppeteer');

(async () => {
    const browser = await puppeteer.launch({
        headless: 'new',
        args: ['--no-sandbox', '--disable-setuid-sandbox', '--disable-dev-shm-usage']
    });
    const page = await browser.newPage();
    
    // Set viewport for consistent screenshots
    await page.setViewport({ width: 1400, height: 2191, deviceScaleFactor: 2 });
    
    console.log('Navigating to dashboard...');
    await page.goto('http://localhost:8090', { 
        waitUntil: 'networkidle2',
        timeout: 30000 
    });
    
    // Wait for dashboard to fully load - wait for telemetry data
    await page.waitForFunction(() => {
        const versionEl = document.getElementById('jvmVersion');
        return versionEl && versionEl.textContent !== '-' && versionEl.textContent !== '';
    }, { timeout: 15000 });
    
    // Small delay for animations/rendering
    await new Promise(r => setTimeout(r, 1000));
    
    console.log('Taking screenshot...');
    await page.screenshot({ 
        path: 'docs/dashboard.png',
        fullPage: true,
        type: 'png'
    });
    
    console.log('Screenshot saved to docs/dashboard.png');
    await browser.close();
})().catch(err => {
    console.error('Error:', err);
    process.exit(1);
});