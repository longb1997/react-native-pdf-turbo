const path = require('path');
const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

// The library lives one directory up. We consume it directly from source
// (root `index.ts`) so changes are picked up without a build step.
const root = path.resolve(__dirname, '..');

const modules = ['react', 'react-native', 'react-native-fs'];

function escape(string) {
  return string.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('metro-config').MetroConfig}
 */
const config = {
  // Watch the library source so edits above `example/` trigger a reload.
  watchFolders: [root],
  resolver: {
    // Force a single copy of react / react-native / react-native-fs (the
    // example's), otherwise Metro would also pick up the duplicates in the
    // root `node_modules` and the app would crash with "Invalid hook call".
    // Combine into one RegExp — `blockList` takes a single RegExp.
    blockList: new RegExp(
      modules
        .map(m => `^${escape(path.join(root, 'node_modules', m))}\\/.*$`)
        .join('|'),
    ),
    extraNodeModules: modules.reduce((acc, name) => {
      acc[name] = path.join(__dirname, 'node_modules', name);
      return acc;
    }, {}),
    nodeModulesPaths: [path.resolve(__dirname, 'node_modules')],
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
