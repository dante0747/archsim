/**
 * Applies the saved theme (dark by default) before first paint to avoid a flash. The app's layout
 * and the public website's (ADR-0078) both run it; the choice is the theme picker's
 * (components/ui/theme-toggle.tsx), stored in this browser only.
 */
export const THEME_SCRIPT = `(function(){var d=true;try{var t=localStorage.getItem('archsim-theme-studio');d=t==='light'?false:t==='system'?window.matchMedia('(prefers-color-scheme: dark)').matches:true;}catch(e){}if(d)document.documentElement.classList.add('dark');})();`;
