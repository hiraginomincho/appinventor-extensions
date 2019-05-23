"use strict";

console.log("COCO-SSD: Using TensorFlow.js version " + tf.version.tfjs);

const COCO_SSD_MODEL_PATH = "https://storage.googleapis.com/tfjs-models/savedmodel/ssdlite_mobilenet_v2/tensorflowjs_model.pb";
const COCO_SSD_WEIGHT_PATH = "https://storage.googleapis.com/tfjs-models/savedmodel/ssdlite_mobilenet_v2/weights_manifest.json";
// const COCO_SSD_MODEL_PATH = "https://storage.googleapis.com/tfjs-models/savedmodel/ssd_mobilenet_v2/tensorflowjs_model.pb";
// const COCO_SSD_WEIGHT_PATH = "https://storage.googleapis.com/tfjs-models/savedmodel/ssd_mobilenet_v2/weights_manifest.json";

var scale;

var MAX_NUM_BOXES = 20;

let cocossd;
const cocossdDemo = async () => {
  try {
    cocossd = await tf.loadFrozenModel(COCO_SSD_MODEL_PATH, COCO_SSD_WEIGHT_PATH);
    const result = await cocossd.executeAsync(tf.zeros([1, 300, 300, 3]));
    result.map(async (t) => await t.data());
    result.map(async (t) => t.dispose());
    console.log("COCO-SSD: ready");
    COCOSSD.ready();
  } catch(error) {
    console.log("COCO-SSD: " + error);
  }
};

async function detect(pixels) {
  try {
    const batched = tf.tidy(() => {
      return tf.fromPixels(pixels).expandDims(0);
    });
    const height = batched.shape[1];
    const width = batched.shape[2];
    const result = await cocossd.executeAsync(batched);
    const scores = result[0].dataSync();
    const boxes = result[1].dataSync();
    batched.dispose();
    tf.dispose(result);
    const [maxScores, classes] = calculateMaxScores(scores, result[0].shape[1], result[0].shape[2]);
    const prevBackend = tf.getBackend();
    tf.setBackend('cpu');
    const indexTensor = tf.tidy(() => {
      const boxes2 = tf.tensor2d(boxes, [result[1].shape[1], result[1].shape[3]]);
      return tf.image.nonMaxSuppression(boxes2, maxScores, MAX_NUM_BOXES, 0.5, 0.5);
    });
    const indexes = indexTensor.dataSync();
    indexTensor.dispose();
    tf.setBackend(prevBackend);
    buildDetectedObjects(width, height, boxes, maxScores, indexes, classes);
  } catch (error) {
    console.log("COCO-SSD: " + error);
  }
}

function buildDetectedObjects(width, height, boxes, scores, indexes, classes) {
  const count = indexes.length;
  const objects = [];
  const objectsList = [];
  for (let i = 0; i < count; i++) {
    const bbox = [];
    for (let j = 0; j < 4; j++) {
      bbox[j] = boxes[indexes[i] * 4 + j];
    }
    const minY = bbox[0] * height;
    const minX = bbox[1] * width;
    const maxY = bbox[2] * height;
    const maxX = bbox[3] * width;
    bbox[0] = minX;
    bbox[1] = minY;
    bbox[2] = maxX - minX;
    bbox[3] = maxY - minY;
    objects.push({
      bbox: bbox,
      class: COCO_CLASSES[classes[indexes[i]] + 1].displayName,
      score: scores[indexes[i]]
    });
    objectsList.push([COCO_CLASSES[classes[indexes[i]] + 1].displayName, scores[indexes[i]]]);
  }
  console.log("COCO-SSD: objects are " + JSON.stringify(objects));
  COCOSSD.reportResult(JSON.stringify(objects));
  drawBoundingBoxes(objects);
}

function calculateMaxScores(scores, numBoxes, numClasses) {
  const maxes = [];
  const classes = [];
  for (let i = 0; i < numBoxes; i++) {
    let max = Number.MIN_VALUE;
    let index = -1;
    for (let j = 0; j < numClasses; j++) {
      if (scores[i * numClasses + j] > max) {
        max = scores[i * numClasses + j];
        index = j;
      }
    }
    maxes[i] = max;
    classes[i] = index;
  }
  return [maxes, classes];
}

function clear() {
  ctx.clearRect(0, 0, overlay.width, overlay.height);
}

function drawBoundingBoxes(objects) {
  clear();
  for (let i = 0; i < objects.length; i++) {
    ctx.beginPath();
    ctx.rect(...objects[i].bbox);
    ctx.lineWidth = 4;
    ctx.strokeStyle = "#00ff00";
    ctx.fillStyle = "#00ff00";
    ctx.stroke();
    var displayText = objects[i].score.toFixed(3) + ' ' + objects[i].class;
    var width = ctx.measureText(displayText).width;
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(objects[i].bbox[0], objects[i].bbox[1] > 10 ? objects[i].bbox[1] - 17 : 0, width, 12);
    ctx.fillStyle = "#0000ff";
    ctx.fillText(
        objects[i].score.toFixed(3) + ' ' + objects[i].class, objects[i].bbox[0],
        objects[i].bbox[1] > 10 ? objects[i].bbox[1] - 5 : 10);
  }
}

var overlay = document.createElement("canvas");
overlay.style.position = "absolute";
overlay.width = 0;
overlay.height = 0;
var ctx = overlay.getContext("2d");
ctx.font = "12px sans-serif";

var img = document.createElement("img");
img.width = window.innerWidth;
img.style.display = "block";

var video = document.createElement("video");
video.setAttribute("autoplay", "");
video.setAttribute("playsinline", "");
video.width = window.innerWidth;
video.style.display = "none";

var frontFacing = false;
var isVideoMode = false;

document.body.appendChild(overlay);
document.body.appendChild(img);
document.body.appendChild(video);

video.addEventListener("loadedmetadata", function() {
  video.height = this.videoHeight * video.width / this.videoWidth;
  overlay.width = video.width;
  overlay.height = video.height;
  scale = video.width / video.videoWidth;
}, false);

function startVideo() {
  if (isVideoMode) {
    navigator.mediaDevices.getUserMedia({video: {facingMode: frontFacing ? "user" : "environment"}, audio: false})
    .then(stream => (video.srcObject = stream))
    .catch(e => console.log(e));
    video.style.display = "block";
  }
}

function stopVideo() {
  if (isVideoMode && video.srcObject) {
    video.srcObject.getTracks().forEach(t => t.stop());
    video.style.display = "none";
  }
}

function toggleCameraFacingMode() {
  frontFacing = !frontFacing;
  stopVideo();
  startVideo();
}

function detectImageData(imageData) {
  if (!isVideoMode) {
    img.onload = function() {
      clear();
      console.log("before: overlay is " + overlay.width + " by " + overlay.height);
      overlay.width = img.width;
      overlay.height = img.height;
      console.log("after: overlay is " + overlay.width + " by " + overlay.height);
      scale = img.width / img.naturalWidth;
      detect(img);
    }
    img.src = "data:image/png;base64," + imageData;
  }
}

function detectVideoData() {
  if (isVideoMode) {
    detect(video);
  }
}

function setInputMode(inputMode) {
  if (inputMode === "image" && isVideoMode) {
    clear();
    stopVideo();
    isVideoMode = false;
    img.style.display = "block";
  } else if (inputMode === "video" && !isVideoMode) {
    clear();
    img.style.display = "none";
    isVideoMode = true;
    startVideo();
  }
}

window.addEventListener("resize", function() {
  console.log("in resize");
  // clear();
  img.width = window.innerWidth;
  video.width = window.innerWidth;
  video.height = video.videoHeight * window.innerWidth / video.videoWidth;
  if (isVideoMode) {
    overlay.width = video.width;
    overlay.height = video.height;
    scale = video.width / video.videoWidth;
  }
});

cocossdDemo();
