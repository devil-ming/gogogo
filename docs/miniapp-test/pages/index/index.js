Page({
  data: {
    wgs84: { lat: '--', lng: '--', accuracy: '--', speed: '--', altitude: '--' },
    gcj02: { lat: '--', lng: '--' },
    address: { name: '--', address: '--' },
    mockInfo: { isMock: false, message: '未获取定位' },
    lastUpdate: '--',
    loading: false,
    watchStatus: '未启动',
    watchInfo: '--'
  },

  onLoad() {
    this.getLocation();
  },

  // 获取定位（核心方法）
  getLocation() {
    const that = this;
    this.setData({ loading: true });

    // 先获取 WGS84
    wx.getLocation({
      type: 'wgs84',
      isHighAccuracy: true,
      highAccuracyExpireTime: 5000,
      success(res) {
        console.log('=== WGS84 原始坐标 ===');
        console.log('纬度:', res.latitude);
        console.log('经度:', res.longitude);
        console.log('精度:', res.accuracy, '米');
        console.log('速度:', res.speed, 'm/s');
        console.log('高度:', res.altitude);
        console.log('垂直精度:', res.verticalAccuracy);
        console.log('水平精度:', res.horizontalAccuracy);

        const wgs84Data = {
          lat: res.latitude.toFixed(8),
          lng: res.longitude.toFixed(8),
          accuracy: res.accuracy != null ? res.accuracy.toFixed(1) + ' 米' : '未知',
          speed: res.speed != null ? res.speed.toFixed(2) + ' m/s' : '未知',
          altitude: res.altitude != null ? res.altitude.toFixed(2) + ' 米' : '未知'
        };

        // 注意: wx.getLocation 不返回 mock 标记, 这里只做量级提示。
        // accuracy 接近 8 或 35 说明读到的很可能是影梭注入的位置 (这两个是
        // ServiceGo 里的 ACCURACY_GPS_M / ACCURACY_NETWORK_M);
        // 出现其他量级 (几十到几百米) 说明定位来自影梭之外的来源。
        let accuracyHint = '未知来源';
        const acc = res.accuracy;
        if (acc != null) {
          if (Math.abs(acc - 8) < 1.5) {
            accuracyHint = '疑似影梭 GPS (accuracy≈8)';
          } else if (Math.abs(acc - 35) < 1.5) {
            accuracyHint = '疑似影梭 NETWORK (accuracy≈35)';
          } else if (acc < 30) {
            accuracyHint = '其他 GPS 来源 (accuracy=' + acc + ')';
          } else if (acc < 500) {
            accuracyHint = '基站/WiFi 定位 (accuracy=' + acc + ')';
          } else {
            accuracyHint = 'IP 或粗定位 (accuracy=' + acc + ')';
          }
        }

        that.setData({
          wgs84: wgs84Data,
          mockInfo: {
            isMock: acc != null && (Math.abs(acc - 8) < 1.5 || Math.abs(acc - 35) < 1.5),
            message: accuracyHint
          }
        });

        // 再获取 GCJ02
        that.getGCJ02(res.latitude, res.longitude);
      },
      fail(err) {
        console.error('WGS84定位失败:', err);
        that.setData({
          loading: false,
          mockInfo: { isMock: false, message: '定位失败: ' + err.errMsg }
        });
        wx.showToast({ title: '定位失败', icon: 'none' });
      }
    });
  },

  // 获取 GCJ02 坐标
  getGCJ02(wgsLat, wgsLng) {
    const that = this;
    wx.getLocation({
      type: 'gcj02',
      success(res) {
        console.log('=== GCJ02 坐标 ===');
        console.log('纬度:', res.latitude);
        console.log('经度:', res.longitude);

        that.setData({
          gcj02: {
            lat: res.latitude.toFixed(8),
            lng: res.longitude.toFixed(8)
          }
        });

        // 获取地址
        that.getAddress(res.latitude, res.longitude);
      },
      fail(err) {
        console.error('GCJ02定位失败:', err);
        that.setData({ loading: false });
      }
    });
  },

  // 获取地址描述
  getAddress(lat, lng) {
    const that = this;

    wx.chooseLocation({
      latitude: lat,
      longitude: lng,
      success(res) {
        console.log('=== 地址解析 ===');
        console.log('名称:', res.name);
        console.log('地址:', res.address);
        console.log('纬度:', res.latitude);
        console.log('经度:', res.longitude);

        that.setData({
          address: {
            name: res.name || '无名位置',
            address: res.address || '无法解析地址'
          },
          lastUpdate: new Date().toLocaleString('zh-CN'),
          loading: false
        });
      },
      fail(err) {
        console.error('地址解析失败:', err);
        that.setData({
          address: {
            name: '地址解析失败',
            address: err.errMsg || '未知错误'
          },
          lastUpdate: new Date().toLocaleString('zh-CN'),
          loading: false
        });
      }
    });
  },

  // 刷新定位
  onRefresh() {
    this.getLocation();
  },

  /**
   * 持续定位测试。带地图蓝点的小程序用的是 startLocationUpdate + onLocationChange,
   * 这与 getLocation 是微信内部不同的代码路径, 需要单独验证是否同样吃系统 mock。
   */
  onStartWatch() {
    const that = this;
    if (this._watching) {
      wx.stopLocationUpdate({ success() {} });
      this._watching = false;
      this.setData({ watchStatus: '已停止', watchInfo: '--' });
      return;
    }
    wx.startLocationUpdate({
      success() {
        that._watching = true;
        that.setData({ watchStatus: '监听中…' });
        wx.onLocationChange(res => {
          // 与单次 getLocation 的返回结构一致, 但来源是持续定位通道
          const line = `${res.latitude.toFixed(8)}, ${res.longitude.toFixed(8)}  acc=${res.accuracy}`;
          that.setData({ watchInfo: line });
          console.log('=== onLocationChange ===', res.latitude, res.longitude, 'acc=', res.accuracy);
        });
      },
      fail(err) {
        that.setData({ watchStatus: '启动失败: ' + (err.errMsg || '未知') });
      }
    });
  },

  // 复制 WGS84 坐标
  copyWGS84() {
    const { wgs84 } = this.data;
    const text = `${wgs84.lng},${wgs84.lat}`;
    wx.setClipboardData({
      data: text,
      success() {
        wx.showToast({ title: 'WGS84已复制', icon: 'success' });
      }
    });
  },

  // 复制 GCJ02 坐标
  copyGCJ02() {
    const { gcj02 } = this.data;
    const text = `${gcj02.lng},${gcj02.lat}`;
    wx.setClipboardData({
      data: text,
      success() {
        wx.showToast({ title: 'GCJ02已复制', icon: 'success' });
      }
    });
  },

  // 复制全部信息
  copyAll() {
    const { wgs84, gcj02, address, lastUpdate } = this.data;
    const text = `影梭定位测试
时间: ${lastUpdate}

WGS84 (GPS原始):
纬度: ${wgs84.lat}
经度: ${wgs84.lng}
精度: ${wgs84.accuracy}

GCJ02 (微信/腾讯/高德):
纬度: ${gcj02.lat}
经度: ${gcj02.lng}

地址: ${address.address}
名称: ${address.name}`;

    wx.setClipboardData({
      data: text,
      success() {
        wx.showToast({ title: '全部信息已复制', icon: 'success' });
      }
    });
  }
});
